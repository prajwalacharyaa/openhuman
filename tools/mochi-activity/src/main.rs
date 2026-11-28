//! mochi-activity: turns what Mochi is doing into plain steps for the phone's "What Mochi is doing" page.
//!
//! The Rust version of tiny-setup's Python `/usr/local/sbin/mochi-activity` (kept there as the fallback): the same
//! events, the same words, a few MB instead of ~10 MB plus an 8 MB `journalctl` child. Runs as root, no AI, nothing
//! written to disk except SY's hiring evidence (helpers.jsonl), exactly like the Python one.
//!
//! Grouping, as the app shows it:
//!   task   one request (a typed message, or something asked on a call): its first words, its chat (thread)
//!   step   one thing done for it: a search, a command, a file, a helper, memory (main row: plain words + detail)
//!   line   what happened inside a step: Claude's own words, each tool's result summarised (secrets masked)
//!   usage  tokens a model call used (model, new / cached / output), for the per-step and daily counts
//! Never anything from December's phone (contacts, calls, messages): those show only as "Using your phone". A long
//! task finished outside a call sends a "Mochi finished" notice. Events go to mochi-ring (POST /activity, loopback).
//!
//! Sources:
//!   ~openhuman/.claude/projects/*/*.jsonl                 Mochi's Claude Code sessions: each tool it uses
//!   ~openhuman/.claude/projects/*/*/subagents/*.jsonl     helper agents (meta.json: their type and task)
//!   the journal of openhuman.service (libsystemd, no journalctl child): calls, Claude's answers on calls
//!
//! Usage:
//!   mochi-activity                 the service
//!   mochi-activity --dry-run       follows the same sources but prints events, notices, helper-log lines and the
//!                                  programs it would start (E/N/H/S lines) instead of sending or starting anything
//!   mochi-activity --replay ROOT   test mode: reads a recorded tree under ROOT once from the start (ROOT/journal.txt,
//!                                  ROOT/home/openhuman/.claude/projects/...), fixed clock and ids; prints the same
//!                                  lines as tiny-setup's scripts/activity-golden does for the Python one
//!
//! Python's semantics are kept on purpose where they show in the events: lengths and cuts count characters, not
//! bytes; "whitespace" is Python's (str.split / strip / splitlines / regex \s); non-text values print the way
//! Python's str() prints them; JSON is written like json.dumps (", ", ": ", non-ASCII as \uXXXX).

use regex::Regex;
use serde_json::{Map, Value};
use std::collections::{HashMap, HashSet};
use std::fs::{self, File, OpenOptions};
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const PROJECTS: &str = "/home/openhuman/.claude/projects";
const REPOS: &str = "/home/openhuman/OpenHuman/projects/github/";
const CORE_AUTH: &str = "/home/openhuman/OpenHuman/core-auth.conf";
const RING: &str = "127.0.0.1:3160";
const SESSIONS: &str = "/home/openhuman/OpenHuman/.openhuman/claude-code-sessions.json";
const DONE_NOTICE_S: f64 = 60.0; // a task that ran this long (or used this many steps) gets a "Mochi finished" notice
const DONE_NOTICE_STEPS: u32 = 5;
const AGENTS_CONF: &str = "/etc/mochi/agents.json"; // Mochi's specialist agents: look, and whether a finished job notifies
const AGENT_NOTES: &str = "/home/openhuman/OpenHuman/projects/agent-notes";
const HELPERS_LOG: &str = "/var/lib/mochi-hiring/helpers.jsonl"; // every helper Mochi starts, for SY's hiring
const THREADS: &str = "/home/openhuman/OpenHuman/workspace/memory/conversations/threads.jsonl";
const CODING_HELPERS: [&str; 6] = ["builder", "planner", "quick-edit", "Explore", "explore", "statusline-setup"];
const FRESH: f64 = 15.0 * 60.0; // only follow transcripts written in the last 15 minutes
const FAKE_NOW: f64 = 1_700_000_000.0; // --replay's clock (scripts/activity-golden uses the same)

#[derive(Clone, Copy, PartialEq)]
enum Mode {
    Live,
    Dry,
    Replay,
}

struct Cfg {
    mode: Mode,
    root: String,
}

static CFG: OnceLock<Cfg> = OnceLock::new();
static PENDING: Mutex<Vec<String>> = Mutex::new(Vec::new());
static CALL_ON: AtomicBool = AtomicBool::new(false);
static IDS: AtomicU64 = AtomicU64::new(0);
static HIRE_AT: Mutex<f64> = Mutex::new(0.0);
static URANDOM: Mutex<Option<File>> = Mutex::new(None);

fn cfg() -> &'static Cfg {
    CFG.get().expect("config set in main")
}

/// A server path (under ROOT in --replay).
fn at(path: &str) -> String {
    format!("{}{}", cfg().root, path)
}

fn now() -> f64 {
    if cfg().mode == Mode::Replay {
        return FAKE_NOW;
    }
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs_f64()).unwrap_or(0.0)
}

fn out_line(line: &str) {
    let mut o = std::io::stdout().lock();
    let _ = writeln!(o, "{line}");
}

/// Its own messages: the journal for the service, stderr in the test modes (stdout carries the events there).
fn log(msg: &str) {
    if cfg().mode == Mode::Live {
        println!("{msg}");
    } else {
        eprintln!("{msg}");
    }
}

// ---------------------------------------------------------------- Python's text rules

fn is_space(c: char) -> bool {
    // Python's str.isspace(): Unicode White_Space plus the four separators \x1c-\x1f
    c.is_whitespace() || ('\x1c'..='\x1f').contains(&c)
}

fn strip(s: &str) -> &str {
    s.trim_matches(is_space)
}

fn split_ws(s: &str) -> impl Iterator<Item = &str> {
    s.split(is_space).filter(|w| !w.is_empty())
}

fn join_ws(s: &str) -> String {
    split_ws(s).collect::<Vec<_>>().join(" ")
}

/// str.splitlines()
fn splitlines(s: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let mut start = 0;
    let mut it = s.char_indices().peekable();
    while let Some((i, c)) = it.next() {
        if matches!(c, '\n' | '\r' | '\x0b' | '\x0c' | '\x1c' | '\x1d' | '\x1e' | '\u{85}' | '\u{2028}' | '\u{2029}') {
            out.push(&s[start..i]);
            let mut end = i + c.len_utf8();
            if c == '\r' {
                if let Some(&(_, '\n')) = it.peek() {
                    it.next();
                    end += 1;
                }
            }
            start = end;
        }
    }
    if start < s.len() {
        out.push(&s[start..]);
    }
    out
}

/// Universal newlines on bytes (what Python's text-mode files and pipes do): \r\n, \r and \n end a line.
fn ulines(b: &[u8]) -> Vec<&[u8]> {
    let mut out = Vec::new();
    let (mut s, mut i) = (0, 0);
    while i < b.len() {
        match b[i] {
            b'\n' => {
                out.push(&b[s..i]);
                i += 1;
                s = i;
            }
            b'\r' => {
                out.push(&b[s..i]);
                i += 1;
                if i < b.len() && b[i] == b'\n' {
                    i += 1;
                }
                s = i;
            }
            _ => i += 1,
        }
    }
    if s < b.len() {
        out.push(&b[s..]);
    }
    out
}

fn clen(s: &str) -> usize {
    s.chars().count()
}

/// s[:n] in characters
fn cut(s: &str, n: usize) -> &str {
    match s.char_indices().nth(n) {
        Some((i, _)) => &s[..i],
        None => s,
    }
}

/// s[n:] in characters
fn after(s: &str, n: usize) -> &str {
    match s.char_indices().nth(n) {
        Some((i, _)) => &s[i..],
        None => "",
    }
}

fn capitalize(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        None => String::new(),
        Some(f) => f.to_uppercase().collect::<String>() + &c.as_str().to_lowercase(),
    }
}

fn short(text: &str, n: usize) -> String {
    let t = join_ws(text);
    if clen(&t) <= n {
        t
    } else {
        format!("{}…", cut(&t, n - 1))
    }
}

fn thousands(n: usize) -> String {
    let d = n.to_string();
    let mut out = String::new();
    for (i, c) in d.chars().enumerate() {
        if i > 0 && (d.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(c);
    }
    out
}

fn truthy(v: &Value) -> bool {
    match v {
        Value::Null => false,
        Value::Bool(b) => *b,
        Value::Number(n) => n.as_f64().map(|f| f != 0.0).unwrap_or(true),
        Value::String(s) => !s.is_empty(),
        Value::Array(a) => !a.is_empty(),
        Value::Object(o) => !o.is_empty(),
    }
}

fn py_float(f: f64) -> String {
    if f.is_nan() {
        return "nan".into();
    }
    if f.is_infinite() {
        return if f > 0.0 { "inf".into() } else { "-inf".into() };
    }
    let a = f.abs();
    if a != 0.0 && !(1e-4..1e16).contains(&a) {
        let s = format!("{f:e}");
        let (m, e) = s.split_once('e').unwrap_or((&s, "0"));
        let e: i32 = e.parse().unwrap_or(0);
        return format!("{}e{}{:02}", m, if e < 0 { '-' } else { '+' }, e.abs());
    }
    let s = format!("{f}");
    if s.contains('.') {
        s
    } else {
        s + ".0"
    }
}

fn printable(c: char) -> bool {
    c == ' '
        || !(c.is_control()
            || c.is_whitespace()
            || matches!(c, '\u{ad}' | '\u{200b}'..='\u{200f}' | '\u{202a}'..='\u{202e}' | '\u{2060}'..='\u{2064}' | '\u{feff}'))
}

fn py_repr_str(s: &str) -> String {
    let q = if s.contains('\'') && !s.contains('"') { '"' } else { '\'' };
    let mut out = String::new();
    out.push(q);
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c == q => {
                out.push('\\');
                out.push(c);
            }
            c if !printable(c) => {
                let n = c as u32;
                if n < 0x100 {
                    out.push_str(&format!("\\x{n:02x}"));
                } else if n < 0x10000 {
                    out.push_str(&format!("\\u{n:04x}"));
                } else {
                    out.push_str(&format!("\\U{n:08x}"));
                }
            }
            c => out.push(c),
        }
    }
    out.push(q);
    out
}

fn py_num(n: &serde_json::Number) -> String {
    if n.is_f64() {
        py_float(n.as_f64().unwrap_or(0.0))
    } else {
        n.to_string()
    }
}

/// repr() of a JSON value as Python holds it
fn py_repr(v: &Value) -> String {
    match v {
        Value::Null => "None".into(),
        Value::Bool(b) => (if *b { "True" } else { "False" }).into(),
        Value::Number(n) => py_num(n),
        Value::String(s) => py_repr_str(s),
        Value::Array(a) => format!("[{}]", a.iter().map(py_repr).collect::<Vec<_>>().join(", ")),
        Value::Object(o) => format!(
            "{{{}}}",
            o.iter().map(|(k, v)| format!("{}: {}", py_repr_str(k), py_repr(v))).collect::<Vec<_>>().join(", ")
        ),
    }
}

/// str() of a JSON value as Python holds it
fn py_str(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        other => py_repr(other),
    }
}

fn get<'a>(v: &'a Value, key: &str) -> Option<&'a Value> {
    v.as_object().and_then(|o| o.get(key))
}

/// str(d.get(key, ""))
fn gs(v: &Value, key: &str) -> String {
    get(v, key).map(py_str).unwrap_or_default()
}

/// d.get(key) truthy?
fn has(v: &Value, key: &str) -> bool {
    get(v, key).map(truthy).unwrap_or(false)
}

fn is_str(v: Option<&Value>, s: &str) -> bool {
    matches!(v, Some(Value::String(x)) if x == s)
}

/// int(d.get(key) or 0)
fn int_of(v: Option<&Value>) -> i64 {
    match v {
        Some(Value::Number(n)) => n.as_i64().or_else(|| n.as_u64().map(|u| u as i64)).or_else(|| n.as_f64().map(|f| f as i64)).unwrap_or(0),
        Some(Value::Bool(true)) => 1,
        Some(Value::String(s)) => strip(s).parse().unwrap_or(0),
        _ => 0,
    }
}

/// A dict key the way Python compares them (enough for tool and message ids)
fn key_of(v: Option<&Value>) -> String {
    match v {
        None => "s:".into(),
        Some(Value::String(s)) => format!("s:{s}"),
        Some(other) => format!("v:{other}"),
    }
}

fn basename(p: &str) -> &str {
    p.rsplit('/').next().unwrap_or(p)
}

fn dirname(p: &str) -> &str {
    // os.path.dirname: the head up to the last '/', its trailing slashes dropped unless it is all slashes
    match p.rfind('/') {
        None => "",
        Some(i) => {
            let head = &p[..=i];
            let t = head.trim_end_matches('/');
            if t.is_empty() {
                head
            } else {
                t
            }
        }
    }
}

fn py_join(a: &str, b: &str) -> String {
    if b.starts_with('/') {
        b.to_string()
    } else if a.is_empty() || a.ends_with('/') {
        format!("{a}{b}")
    } else {
        format!("{a}/{b}")
    }
}

fn is_dir(p: &str) -> bool {
    fs::metadata(p).map(|m| m.is_dir()).unwrap_or(false)
}

// ---------------------------------------------------------------- JSON out, written like Python's json.dumps

fn jstr(s: &str, out: &mut String) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\x08' => out.push_str("\\b"),
            '\x0c' => out.push_str("\\f"),
            ' '..='~' => out.push(c),
            c => {
                let mut buf = [0u16; 2];
                for u in c.encode_utf16(&mut buf) {
                    out.push_str(&format!("\\u{u:04x}"));
                }
            }
        }
    }
    out.push('"');
}

enum J<'a> {
    S(&'a str),
    I(i64),
}

fn jobj(fields: &[(&str, J)]) -> String {
    let mut out = String::from("{");
    for (i, (k, v)) in fields.iter().enumerate() {
        if i > 0 {
            out.push_str(", ");
        }
        jstr(k, &mut out);
        out.push_str(": ");
        match v {
            J::S(s) => jstr(s, &mut out),
            J::I(n) => out.push_str(&n.to_string()),
        }
    }
    out.push('}');
    out
}

// ---------------------------------------------------------------- patterns

const SP: &str = r"[\s\x1c-\x1f]"; // Python's \s
const NSP: &str = r"[^\s\x1c-\x1f]"; // Python's \S

struct Res {
    bearer: Regex,
    secret: Regex,
    titles: Regex,
    ids: Regex,
    paras: Regex,
    voice_prefix: Regex,
    cd: Regex,
    env: Regex,
    handback: Regex,
    agent_name: Regex,
    voice_tool: Regex,
    voice_result: Regex,
    call_start: Regex,
    call_end: Regex,
    claude_back: Regex,
    scheme: Regex,
}

fn re() -> &'static Res {
    static R: OnceLock<Res> = OnceLock::new();
    R.get_or_init(|| {
        let r = |p: String| Regex::new(&p).expect("pattern");
        Res {
            bearer: r(format!(r"(?i)(bearer{SP}+){NSP}+")),
            // Python's pattern ends (?!•)\S+: the look-ahead is done by hand in mask_secrets
            secret: r(format!(r"(?i)(?:token|key|secret|password|passwd)[\w-]*{SP}*[=:]{SP}*")),
            titles: r(format!(r#""title"{SP}*:{SP}*"([^"]{{3,120}})""#)),
            ids: r(format!(r#""id"{SP}*:"#)),
            paras: r(format!(r"\n{SP}*\n")),
            voice_prefix: r(format!(r"^\(Asked on a voice call[^)]*\){SP}*")),
            cd: r(format!(r"(?s)^{SP}*cd{SP}+({NSP}+){SP}*&&{SP}*(.*)")),
            env: r(r"^[A-Z_][A-Z0-9_]*=".into()),
            handback: r(concat!(
                r"(?i)\b(outside (?:my|of my) (?:area|job|scope)|not (?:my|in my) (?:area|job)|mochi (?:will|should|can) ",
                r"(?:handle|do|take)|(?:can't|cannot|couldn't|unable to) (?:do|finish|complete|handle) (?:this|that|it))"
            )
            .into()),
            agent_name: r(r"^[a-z0-9][a-z0-9_-]{0,40}$".into()),
            voice_tool: r(format!(r"\[voice-live\] tool ({NSP}+): (.*)$")),
            voice_result: r(format!(r"\[voice-live\] result ({NSP}+): (.*)$")),
            call_start: r(r"\[voice-live\] session started".into()),
            call_end: r(r"\[voice-live\] session ended".into()),
            claude_back: r(r"\[voice-live\] Claude's answer arrived".into()),
            scheme: r(r"^https?://".into()),
        }
    })
}

fn mask_bearer(text: &str) -> String {
    re().bearer.replace_all(text, "${1}•••").into_owned()
}

/// SECRET.sub: key=value → key=•••, unless the value already starts with • (Python's (?!•) look-ahead)
fn mask_secrets(text: &str) -> String {
    let rx = &re().secret;
    let mut out = String::new();
    let (mut last, mut pos) = (0, 0);
    while pos <= text.len() {
        let Some(m) = rx.find_at(text, pos) else { break };
        let rest = &text[m.end()..];
        match rest.chars().next() {
            Some(c) if c != '•' && !is_space(c) => {
                let run = rest.find(is_space).unwrap_or(rest.len());
                out.push_str(&text[last..m.end()]);
                out.push_str("•••");
                last = m.end() + run;
                pos = last;
            }
            _ => {
                // this start can't match: Python tries the next character
                pos = m.start() + text[m.start()..].chars().next().map(|c| c.len_utf8()).unwrap_or(1);
            }
        }
    }
    out.push_str(&text[last..]);
    out
}

/// Text for a notice: anything secret-looking masked, line breaks kept.
fn mask(text: &str, limit: usize) -> String {
    cut(&mask_secrets(&mask_bearer(text)), limit).to_string()
}

/// The detail line: the actual command, query, address or path, with anything secret-looking masked.
fn clean_detail(text: &str) -> String {
    cut(&mask_secrets(&mask_bearer(&join_ws(text))), 300).to_string()
}

fn first_line(text: &str, n: usize) -> String {
    for line in splitlines(text) {
        let line = strip(strip(line).trim_matches(|c| "`#*>- ".contains(c)));
        if !line.is_empty() {
            return short(&clean_detail(line), n);
        }
    }
    String::new()
}

/// December's own words in a turn OpenHuman sent Claude (it wraps them in a date, a voice recap and memory).
fn own_words(text: &str) -> String {
    let text = strip(text.split("<memory-context>").next().unwrap_or(""));
    let paras: Vec<&str> = re()
        .paras
        .split(text)
        .map(strip)
        .filter(|p| !p.is_empty() && !p.starts_with('[') && !p.starts_with("Current Date & Time:"))
        .collect();
    match paras.last() {
        None => String::new(),
        Some(p) => strip(&re().voice_prefix.replace(p, "")).to_string(),
    }
}

// ---------------------------------------------------------------- sending

fn token() -> String {
    let Ok(f) = File::open(at(CORE_AUTH)) else { return String::new() };
    for line in BufReader::new(f).lines().map_while(Result::ok) {
        if let Some(v) = line.strip_prefix("OPENHUMAN_CORE_TOKEN=") {
            return strip(v).trim_matches('"').to_string();
        }
    }
    String::new()
}

fn post(path: &str, body: &str) -> Result<(), String> {
    let addr: SocketAddr = RING.parse().map_err(|e| format!("{e}"))?;
    let t = Duration::from_secs(5);
    let mut s = TcpStream::connect_timeout(&addr, t).map_err(|e| format!("<urlopen error {e}>"))?;
    let _ = s.set_read_timeout(Some(t));
    let _ = s.set_write_timeout(Some(t));
    let head = format!(
        "POST {path} HTTP/1.1\r\nAccept-Encoding: identity\r\nContent-Type: application/json\r\nAuthorization: Bearer {}\r\n\
         Content-Length: {}\r\nHost: {RING}\r\nUser-Agent: mochi-activity\r\nConnection: close\r\n\r\n",
        token(),
        body.len()
    );
    s.write_all(head.as_bytes()).and_then(|_| s.write_all(body.as_bytes())).map_err(|e| format!("{e}"))?;
    let mut status = String::new();
    BufReader::new(&s).read_line(&mut status).map_err(|e| format!("{e}"))?;
    let mut parts = status.trim_end().splitn(3, ' ');
    let _ = parts.next();
    let code: u16 = parts.next().and_then(|c| c.parse().ok()).unwrap_or(0);
    if (200..300).contains(&code) {
        Ok(())
    } else {
        Err(format!("HTTP Error {code}: {}", parts.next().unwrap_or("")))
    }
}

fn sender() {
    loop {
        std::thread::sleep(Duration::from_secs(1));
        let batch: Vec<String> = {
            let mut p = PENDING.lock().unwrap_or_else(|e| e.into_inner());
            let n = p.len().min(50);
            p.drain(..n).collect()
        };
        if batch.is_empty() {
            continue;
        }
        let body = format!("{{\"events\": [{}]}}", batch.join(", "));
        if let Err(e) = post("/activity", &body) {
            log(&format!("not sent: {e}")); // mochi-ring restarting: these steps are simply not shown
        }
    }
}

fn notify(title: &str, body: &str) {
    let data = jobj(&[("title", J::S(title)), ("body", J::S(cut(body, 1000)))]);
    match cfg().mode {
        Mode::Live => {
            if let Err(e) = post("/notify", &data) {
                log(&format!("notice not sent: {e}"));
            }
        }
        Mode::Dry => out_line(&format!("N http://{RING}/notify {data}")),
        Mode::Replay => out_line(&format!("N http://{RING}/notify Bearer {} {data}", token())),
    }
}

fn spawn(args: &[&str]) {
    if cfg().mode != Mode::Live {
        out_line(&format!("S {}", args.join(" ")));
        return;
    }
    let child = std::process::Command::new(args[0])
        .args(&args[1..])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn();
    if let Ok(mut c) = child {
        std::thread::spawn(move || {
            let _ = c.wait();
        });
    }
}

fn new_id() -> String {
    if cfg().mode == Mode::Replay {
        return format!("{:016x}", IDS.fetch_add(1, Ordering::SeqCst) + 1);
    }
    let mut b = [0u8; 8];
    let mut g = URANDOM.lock().unwrap_or_else(|e| e.into_inner());
    if g.is_none() {
        *g = File::open("/dev/urandom").ok();
    }
    let ok = g.as_mut().map(|f| f.read_exact(&mut b).is_ok()).unwrap_or(false);
    if !ok {
        let n = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0) as u64;
        b = (n ^ IDS.fetch_add(0x9e37_79b9_7f4a_7c15, Ordering::SeqCst)).to_le_bytes();
    }
    let mut h: String = b.iter().map(|x| format!("{x:02x}")).collect();
    h.replace_range(12..13, "4"); // the first 16 of a uuid4's hex: its version digit is a 4
    h
}

#[derive(Clone)]
struct Usage {
    model: String,
    t_in: i64,
    t_out: i64,
    t_cache_read: i64,
    t_cache_write: i64,
}

/// Queue one event for mochi-ring; returns its id (a step's id is what its lines point at).
#[allow(clippy::too_many_arguments)]
fn emit(lane: &str, who: &str, text: &str, repo: &str, detail: &str, kind: &str, step: &str, task: &str, thread: &str,
        usage: Option<&Usage>) -> String {
    if text.is_empty() && kind != "usage" {
        return String::new();
    }
    let detail = if lane == "phone" { "" } else { detail }; // December's phone data never leaves as a detail
    let eid = new_id();
    let detail = if detail.is_empty() { String::new() } else { clean_detail(detail) };
    let mut f = vec![
        ("id", J::S(&eid)),
        ("at", J::I(now() as i64)),
        ("kind", J::S(kind)),
        ("lane", J::S(lane)),
        ("who", J::S(who)),
        ("text", J::S(cut(text, 160))),
        ("repo", J::S(repo)),
        ("detail", J::S(&detail)),
        ("step", J::S(step)),
        ("task", J::S(task)),
        ("thread", J::S(thread)),
    ];
    if let Some(u) = usage {
        f.extend([
            ("model", J::S(&u.model)),
            ("t_in", J::I(u.t_in)),
            ("t_out", J::I(u.t_out)),
            ("t_cache_read", J::I(u.t_cache_read)),
            ("t_cache_write", J::I(u.t_cache_write)),
        ]);
    }
    let event = jobj(&f);
    if cfg().mode == Mode::Live {
        PENDING.lock().unwrap_or_else(|e| e.into_inner()).push(event);
    } else {
        out_line(&format!("E {event}"));
    }
    eid
}

/// One line for SY's hiring evidence; a temporary helper also triggers the instant check (at most once a minute).
fn log_helper(kind: &str, kind_of: &str, desc: &str, thread: &str) {
    let line = jobj(&[
        ("at", J::I(now() as i64)),
        ("kind", J::S(kind)),
        ("type", J::S(kind_of)),
        ("desc", J::S(cut(desc, 300))),
        ("thread", J::S(thread)),
    ]);
    if cfg().mode == Mode::Dry {
        out_line(&format!("H {line}"));
    } else {
        let path = at(HELPERS_LOG);
        let wrote = (|| -> std::io::Result<()> {
            use std::os::unix::fs::DirBuilderExt;
            fs::DirBuilder::new().recursive(true).mode(0o750).create(dirname(&path))?;
            let mut f = OpenOptions::new().append(true).create(true).open(&path)?;
            f.write_all(format!("{line}\n").as_bytes())
        })();
        if let Err(e) = wrote {
            log(&format!("helper log: {e}"));
            return;
        }
    }
    let temporary = kind == "start" && !CODING_HELPERS.contains(&kind_of) && !is_dir(&py_join(&at(AGENT_NOTES), kind_of));
    let mut hire = HIRE_AT.lock().unwrap_or_else(|e| e.into_inner());
    if temporary && now() - *hire > 60.0 {
        *hire = now();
        spawn(&["/usr/local/sbin/mochi-hire", "watch", "--instant"]);
    }
}

fn agent_conf(name: &str) -> Value {
    let conf = fs::read_to_string(at(AGENTS_CONF)).ok().and_then(|s| serde_json::from_str::<Value>(&s).ok());
    match conf.as_ref().and_then(|c| get(c, name)) {
        Some(v @ Value::Object(_)) if truthy(v) => v.clone(),
        _ => Value::Object(Map::new()),
    }
}

// ---------------------------------------------------------------- what a tool call means in plain words

fn usage_of(msg: &Value) -> Option<Usage> {
    let u = get(msg, "usage")?;
    if !truthy(u) || !u.is_object() {
        return None;
    }
    let model = gs(msg, "model");
    let short_model = ["opus", "sonnet", "haiku"]
        .iter()
        .find(|m| model.contains(*m))
        .map(|m| m.to_string())
        .unwrap_or_else(|| if model.is_empty() { "claude".into() } else { model.clone() });
    let f = |k: &str| int_of(get(u, k).filter(|v| truthy(v)));
    Some(Usage {
        model: short_model,
        t_in: f("input_tokens"),
        t_out: f("output_tokens"),
        t_cache_read: f("cache_read_input_tokens"),
        t_cache_write: f("cache_creation_input_tokens"),
    })
}

/// One short line for a tool's result: what came back, plus its first line (secrets masked).
fn summarize_result(name: &str, content: Option<&Value>) -> String {
    let text = match content {
        Some(Value::Array(a)) => a
            .iter()
            .filter(|b| b.is_object())
            .map(|b| gs(b, "text"))
            .collect::<Vec<_>>()
            .join("\n"),
        Some(v) if truthy(v) => py_str(v),
        _ => String::new(),
    };
    let low = name.to_lowercase();
    let lines: Vec<&str> = splitlines(&text).into_iter().filter(|l| !strip(l).is_empty()).collect();
    if low.contains("web_search") || name == "WebSearch" {
        let titles: Vec<&str> = re().titles.captures_iter(&text).map(|c| c.get(1).map(|m| m.as_str()).unwrap_or("")).collect();
        if !titles.is_empty() {
            let t: Vec<String> = titles.iter().take(3).map(|t| short(t, 40)).collect();
            return format!("{} results: {}", titles.len(), t.join(", "));
        }
        return format!("results: {}", first_line(&text, 140));
    }
    if name == "WebFetch" || low.contains("web_fetch") {
        return format!("read {} characters · {}", thousands(clen(&text)), first_line(&text, 90));
    }
    if low.contains("memory") {
        let hits = re().ids.find_iter(&text).count();
        return if hits > 0 { format!("found {hits} · {}", first_line(&text, 80)) } else { "nothing found".into() };
    }
    match name {
        "Read" | "NotebookRead" => format!("read {} lines", lines.len()),
        "Grep" => {
            if lines.is_empty() {
                "0 matches".into()
            } else {
                format!("{} matches · {}", lines.len(), first_line(&text, 80))
            }
        }
        "Glob" => format!("{} files", lines.len()),
        "Edit" | "Write" | "MultiEdit" | "NotebookEdit" => or(first_line(&text, 100), "saved"),
        "Bash" => {
            let n = if lines.len() > 1 { format!("{} lines · ", lines.len()) } else { String::new() };
            n + &or(first_line(&text, 110), "done, no output")
        }
        "Task" | "Agent" => format!("helper answered: {}", first_line(&text, 110)),
        _ => or(first_line(&text, 120), "done"),
    }
}

fn or(s: String, dflt: &str) -> String {
    if s.is_empty() {
        dflt.to_string()
    } else {
        s
    }
}

fn repo_of(path: &str) -> String {
    match path.strip_prefix(REPOS) {
        Some(rest) => rest.split('/').next().unwrap_or("").to_string(),
        None => String::new(),
    }
}

fn repo_of_v(v: Option<&Value>) -> String {
    match v {
        Some(Value::String(s)) => repo_of(s),
        _ => String::new(),
    }
}

fn base(path: &str) -> String {
    let b = basename(path.trim_end_matches('/'));
    if b.is_empty() {
        path.to_string()
    } else {
        b.to_string()
    }
}

/// Mochi's helpers and common tools → (lane, words)
fn program(prog: &str) -> Option<(&'static str, &'static str)> {
    Some(match prog {
        "mochi-phone" => ("phone", "Using your phone"),
        "mochi-remind" => ("server", "Setting a reminder"),
        "mochi-mail" => ("server", "Writing an email"),
        "mochi-notify" => ("server", "Sending you a notice"),
        "mochi-decide" => ("server", "Asking you to decide"),
        "mochi-call" => ("phone", "Calling you"),
        "mochi-browse" => ("web", "Using the browser"),
        "mochi-shot" => ("web", "Taking a screenshot of a page"),
        "mochi-ask" => ("brain", "Handing this to Claude"),
        "mochi-watch" => ("server", "Setting up a watch"),
        "graphify" => ("server", "Mapping the code"),
        "curl" | "wget" => ("web", "Fetching from the web"),
        _ => return None,
    })
}

struct Desc {
    lane: String,
    text: String,
    repo: String,
    who: Option<String>,
}

fn desc(lane: &str, text: String, repo: String) -> Desc {
    Desc { lane: lane.into(), text, repo, who: None }
}

/// A shell command as plain words (never the command itself).
fn describe_command(cmd: &str, cwd: Option<&Value>) -> Desc {
    let mut repo = repo_of_v(cwd);
    let mut cmd = cmd;
    if let Some(c) = re().cd.captures(cmd) {
        let r = repo_of(&c[1].replace('~', "/home/openhuman"));
        if !r.is_empty() {
            repo = r;
        }
        cmd = c.get(2).map(|m| m.as_str()).unwrap_or("");
    }
    let mut words: Vec<&str> = split_ws(cmd).collect();
    while !words.is_empty() && re().env.is_match(words[0]) {
        words.remove(0);
    }
    if words.is_empty() {
        return desc("server", "Running a command".into(), repo);
    }
    let prog = base(words[0]);
    let sub = if words.len() > 1 && !words[1].starts_with('-') { words[1] } else { "" };
    if let Some((lane, text)) = program(&prog) {
        return desc(lane, text.into(), repo);
    }
    if prog == "mochi-git" {
        let target = if words.len() > 2 && !words[2].starts_with('-') { words[2] } else { "" };
        let repo = if !repo.is_empty() { repo } else if !target.contains('/') { target.to_string() } else { String::new() };
        return desc("github", strip(&format!("GitHub: {sub} {target}")).into(), repo);
    }
    if prog == "git" {
        return desc("github", strip(&format!("git {sub}")).into(), repo);
    }
    if matches!(prog.as_str(), "npm" | "pnpm" | "yarn" | "npx") {
        return desc("server", strip(&format!("Running {prog} {sub}")).into(), repo);
    }
    if prog == "pytest" || (prog.starts_with("python") && cmd.contains("pytest")) {
        return desc("server", "Running the tests".into(), repo);
    }
    desc("server", format!("Running {prog}"), repo)
}

/// What one tool call shows as, or None to skip it.
fn describe_tool(name: &str, inp: &Value, cwd: Option<&Value>) -> Option<Desc> {
    let repo = repo_of_v(cwd);
    let low = name.to_lowercase();
    if name == "ToolSearch" {
        return None;
    }
    if low.contains("web_search") || name == "WebSearch" {
        return Some(desc("web", format!("Searching the web: {}", short(&gs(inp, "query"), 60)), repo));
    }
    if name == "WebFetch" || low.contains("web_fetch") {
        let url = gs(inp, "url");
        let stripped = re().scheme.replace(&url, "");
        let host = stripped.split('/').next().unwrap_or("");
        let text = if host.is_empty() { "Reading a web page".into() } else { format!("Reading {host}") };
        return Some(desc("web", text, repo));
    }
    if low.starts_with("mcp__openhuman__memory") || low.contains("memory") {
        let saving = ["learn", "store", "remember", "save", "add"].iter().any(|k| low.contains(k));
        return Some(desc("memory", if saving { "Saving to memory" } else { "Checking memory" }.into(), repo));
    }
    match name {
        "Bash" => Some(describe_command(&gs(inp, "command"), cwd)),
        "Read" | "NotebookRead" => {
            let path = get(inp, "file_path");
            let r = or(repo_of_v(path), &repo);
            Some(desc("server", format!("Reading {}", base(&path.map(py_str).unwrap_or_default())), r))
        }
        "Grep" => {
            let r = or(repo_of_v(get(inp, "path")), &repo);
            Some(desc("server", format!("Searching the code for {}", short(&gs(inp, "pattern"), 30)), r))
        }
        "Glob" => Some(desc("server", "Looking for files".into(), repo)),
        "Edit" | "Write" | "MultiEdit" | "NotebookEdit" => {
            let path = if has(inp, "file_path") { get(inp, "file_path") } else { get(inp, "notebook_path") };
            let r = or(repo_of_v(path), &repo);
            Some(desc("server", format!("Editing {}", base(&path.map(py_str).unwrap_or_default())), r))
        }
        "Task" | "Agent" => {
            let kind = if has(inp, "subagent_type") { gs(inp, "subagent_type") } else { "helper".into() };
            let what = if has(inp, "description") { gs(inp, "description") } else { gs(inp, "prompt") };
            let mut d = desc("helper", format!("Started: {}", short(&what, 70)), repo);
            d.who = Some(format!("Helper: {kind}"));
            Some(d)
        }
        "Skill" => {
            let s = if has(inp, "skill") {
                gs(inp, "skill")
            } else if has(inp, "name") {
                gs(inp, "name")
            } else {
                String::new()
            };
            Some(desc("brain", format!("Using the {s} skill").replace("  ", " "), repo))
        }
        "TodoWrite" => Some(desc("brain", "Planning the steps".into(), repo)),
        _ => Some(desc("brain", capitalize(&name.replace("mcp__openhuman__", "").replace('_', " ")), repo)),
    }
}

/// The specifics behind a step: the command, the query, the address, the path, the helper's task.
fn detail_of(inp: &Value) -> String {
    for key in ["command", "query", "url", "file_path", "notebook_path", "pattern", "description", "skill", "prompt"] {
        if has(inp, key) {
            let extra = if key == "pattern" && has(inp, "path") { format!("  in {}", gs(inp, "path")) } else { String::new() };
            return format!("{}{extra}", gs(inp, key));
        }
    }
    String::new()
}

// ---------------------------------------------------------------- one transcript

/// Follows one transcript file from its current end, as tasks → steps → lines, with each model call's tokens.
struct Tail {
    path: String,
    who: String,
    thread: String,
    sub: bool,        // a helper agent's own file (…/subagents/…)
    agent_chat: bool, // a direct chat with an agent
    job: String,      // a helper's task as Mochi described it
    pos: u64,
    buf: Vec<u8>,
    task: String, // the request being worked on (a helper's file: its own task)
    task_at: f64,
    steps_in_task: u32,
    step: String,                                     // the latest step (Claude's own words go under it)
    tool_steps: HashMap<String, (String, String, Value)>, // tool_use_id → (step id, tool name, input)
    seen_msgs: HashSet<String>,                       // one usage per model call (a call can span several lines)
}

impl Tail {
    fn new(path: String, who: String, thread: String, job: String, pos: u64) -> Tail {
        let sub = path.contains("/subagents/");
        Tail {
            agent_chat: who.starts_with("Helper: ") && !sub,
            path,
            who,
            thread,
            sub,
            job,
            pos,
            buf: Vec::new(),
            task: String::new(),
            task_at: 0.0,
            steps_in_task: 0,
            step: String::new(),
            tool_steps: HashMap::new(),
            seen_msgs: HashSet::new(),
        }
    }

    fn read(&mut self) {
        let Ok(mut f) = File::open(&self.path) else { return };
        if f.seek(SeekFrom::Start(self.pos)).is_err() {
            return;
        }
        let mut data = Vec::new();
        match (&mut f).take(4_000_000).read_to_end(&mut data) {
            Ok(n) => self.pos += n as u64,
            Err(_) => return,
        }
        self.buf.extend_from_slice(&data);
        let Some(nl) = self.buf.iter().rposition(|&b| b == b'\n') else { return };
        let done: Vec<u8> = self.buf.drain(..=nl).collect();
        for line in ulines(&done) {
            let Ok(entry) = serde_json::from_str::<Value>(&String::from_utf8_lossy(line)) else { continue };
            if !entry.is_object() {
                continue;
            }
            let empty = Value::Object(Map::new());
            let msg = match get(&entry, "message") {
                Some(m @ Value::Object(_)) => m,
                _ => &empty,
            };
            let typ = get(&entry, "type");
            if is_str(typ, "agent-setting") && !self.sub {
                let a = get(&entry, "agentSetting").filter(|v| truthy(v)).map(py_str).unwrap_or_default();
                self.set_agent(&a);
            } else if is_str(typ, "user") && !has(&entry, "isMeta") {
                // isMeta: a skill's text, not December
                self.on_user(get(msg, "content"));
            } else if is_str(typ, "assistant") {
                self.on_assistant(msg, get(&entry, "cwd"));
            }
        }
    }

    /// Claude Code writes the agent a session runs as (`--agent`): its steps are that agent's.
    fn set_agent(&mut self, agent: &str) {
        if re().agent_name.is_match(agent) && self.who != format!("Helper: {agent}") {
            self.who = format!("Helper: {agent}");
            self.agent_chat = true;
        }
    }

    fn new_task(&mut self, words: &str) {
        if self.agent_chat {
            log_helper("chat", after(&self.who, 8), words, "");
        }
        self.task = emit("brain", &self.who, words, "", "", "task", "", "", &self.thread, None);
        self.task_at = now();
        self.steps_in_task = 0;
        self.step.clear();
    }

    fn on_user(&mut self, content: Option<&Value>) {
        let blocks: Vec<Value> = match content {
            Some(Value::Array(a)) => a.clone(),
            Some(c) if truthy(c) => {
                let mut m = Map::new();
                m.insert("type".into(), Value::String("text".into()));
                m.insert("text".into(), c.clone());
                vec![Value::Object(m)]
            }
            _ => vec![],
        };
        for b in &blocks {
            if !b.is_object() {
                continue;
            }
            let typ = get(b, "type");
            if is_str(typ, "tool_result") {
                let (step, name, inp) = self
                    .tool_steps
                    .remove(&key_of(get(b, "tool_use_id")))
                    .unwrap_or_else(|| (String::new(), String::new(), Value::Object(Map::new())));
                if !step.is_empty() && !name.is_empty() {
                    let lane = if py_str(&inp).contains("mochi-phone") { "phone" } else { "brain" };
                    let text = if lane == "phone" { "done".into() } else { summarize_result(&name, get(b, "content")) };
                    emit(lane, &self.who, &text, "", "", "line", &step, &self.task, &self.thread, None);
                }
            } else if is_str(typ, "text") {
                let text = get(b, "text");
                if self.agent_chat {
                    let Some(t) = text.map(|t| t.as_str()).unwrap_or(Some("")) else { continue };
                    if t.starts_with("[Hiring") {
                        // mochi-hire's note in SY's Hiring chat: the job is its first line
                        let first = splitlines(t).first().copied().unwrap_or("");
                        let job = first.trim_matches(|c| "[] ".contains(c)).split(',').next().unwrap_or("");
                        let words = format!("{job} for {}", after(&self.who, 8).to_uppercase());
                        self.new_task(&words);
                        continue;
                    }
                }
                if !self.sub {
                    let words = own_words(&text.map(py_str).unwrap_or_default());
                    if !words.is_empty() {
                        self.new_task(&format!("You: {}", short(&words, 110)));
                    }
                }
            }
        }
        if self.task.is_empty() && self.sub {
            let t = self.helper_title();
            self.new_task(&t);
        }
    }

    fn helper_title(&self) -> String {
        if !self.job.is_empty() {
            return short(&self.job, 110);
        }
        let name = self.who.strip_prefix("Helper: ").unwrap_or(&self.who);
        format!("{} at work", if clen(name) <= 2 { name.to_uppercase() } else { capitalize(name) })
    }

    fn on_assistant(&mut self, msg: &Value, cwd: Option<&Value>) {
        if self.task.is_empty() {
            let t = if self.sub {
                self.helper_title()
            } else if self.agent_chat {
                // a direct chat with an agent: "SY at work", "Mark at work"
                let name = after(&self.who, 8);
                format!("{} at work", if clen(name) <= 2 { name.to_uppercase() } else { capitalize(name) })
            } else {
                "Mochi at work".into()
            };
            self.new_task(&t);
        }
        let mut usage = None;
        if let Some(mid) = get(msg, "id").filter(|v| truthy(v)) {
            if self.seen_msgs.insert(key_of(Some(mid))) {
                usage = usage_of(msg);
            }
        }
        let mut used = false;
        let empty = Value::Object(Map::new());
        let blocks = match get(msg, "content") {
            Some(Value::Array(a)) => a.as_slice(),
            _ => &[],
        };
        for block in blocks {
            if !block.is_object() {
                continue;
            }
            let typ = get(block, "type");
            if is_str(typ, "tool_use") {
                let name = gs(block, "name");
                let raw_inp = get(block, "input");
                let inp = raw_inp.unwrap_or(&empty);
                let Some(d) = describe_tool(&name, inp, cwd) else { continue };
                let who = d.who.clone().unwrap_or_else(|| self.who.clone());
                let u = if used { None } else { usage.as_ref() };
                self.step = emit(&d.lane, &who, &d.text, &d.repo, &detail_of(inp), "step", "", &self.task, &self.thread, u);
                used = used || usage.is_some();
                self.steps_in_task += 1;
                let stored = raw_inp.filter(|v| truthy(v)).cloned().unwrap_or_else(|| Value::Object(Map::new()));
                self.tool_steps.insert(key_of(get(block, "id")), (self.step.clone(), name, stored));
            } else if is_str(typ, "text") {
                let Some(Value::String(full)) = get(block, "text") else { continue };
                if strip(full).is_empty() {
                    continue;
                }
                let words = first_line(full, 160);
                let u = if used { None } else { usage.as_ref() };
                if is_str(get(msg, "stop_reason"), "end_turn") {
                    emit("brain", &self.who, "Finished replying", "", &words, "step", "", &self.task, &self.thread, u);
                    used = used || usage.is_some();
                    self.done(&words, full);
                } else if !self.step.is_empty() {
                    emit("brain", &self.who, &format!("Claude: {words}"), "", "", "line", &self.step, &self.task, &self.thread, None);
                } else {
                    let text = format!("Claude: {}", short(&words, 90));
                    self.step = emit("brain", &self.who, &text, "", &words, "step", "", &self.task, &self.thread, u);
                    used = used || usage.is_some();
                }
            }
        }
        if let Some(u) = usage.as_ref() {
            if !used {
                emit("brain", &self.who, "", "", "", "usage", "", &self.task, &self.thread, Some(u));
            }
        }
    }

    /// A finished task: a notice when it was long, unless December is on a call (the call says it).
    fn done(&mut self, words: &str, full: &str) {
        let call_on = || CALL_ON.load(Ordering::SeqCst);
        let long_task = now() - self.task_at >= DONE_NOTICE_S || self.steps_in_task >= DONE_NOTICE_STEPS;
        if long_task && !self.sub && !self.thread.starts_with("voice-") && !call_on() {
            notify("Mochi finished", if words.is_empty() { "Your request is done." } else { words });
        }
        let agent = if self.sub || self.agent_chat {
            self.who.strip_prefix("Helper: ").unwrap_or(&self.who).to_string()
        } else {
            String::new()
        };
        let said = if !full.is_empty() { full } else { words };
        if !agent.is_empty() && is_dir(&py_join(&at(AGENT_NOTES), &agent)) {
            if self.sub && re().handback.is_match(said) {
                log_helper("handback", &agent, words, "");
            }
            // a specialist finished: its notes may have changed, so the app's Agents screen gets them now
            spawn(&["/usr/local/sbin/mochi-publish", "--agents"]);
            if has(&agent_conf(&agent), "notify") && !call_on() {
                let name = if clen(&agent) <= 2 { agent.to_uppercase() } else { capitalize(&agent) };
                let body = if said.is_empty() { "The check is done." } else { said };
                notify(&format!("{name} finished"), &mask(body, 1000));
            }
        }
        self.steps_in_task = 0;
    }
}

// ---------------------------------------------------------------- finding transcripts

/// The agent a Claude Code session runs as: its transcript starts with {"type": "agent-setting", ...}.
fn agent_setting(path: &str) -> String {
    let Ok(f) = File::open(path) else { return String::new() };
    let mut r = BufReader::new(f);
    let mut seen = 0;
    while seen < 5 {
        let mut raw = Vec::new();
        match r.read_until(b'\n', &mut raw) {
            Ok(0) | Err(_) => break,
            Ok(_) => {}
        }
        for line in ulines(&raw) {
            if seen >= 5 {
                break;
            }
            seen += 1;
            let Ok(line) = std::str::from_utf8(line) else { return String::new() };
            if line.contains("\"agent-setting\"") {
                let Ok(v) = serde_json::from_str::<Value>(line) else { return String::new() };
                let name = get(&v, "agentSetting").filter(|v| truthy(v)).map(py_str).unwrap_or_default();
                return if re().agent_name.is_match(&name) { name } else { String::new() };
            }
        }
    }
    String::new()
}

struct Finder {
    sessions_at: f64,
    sessions: HashMap<String, String>,
    agent_threads: HashMap<String, String>,
}

impl Finder {
    /// The chat a transcript belongs to (OpenHuman's claude-code-sessions.json maps chat → session).
    fn thread_of(&mut self, path: &str) -> String {
        if now() - self.sessions_at > 30.0 {
            let read = fs::read_to_string(at(SESSIONS)).ok().and_then(|s| serde_json::from_str::<Value>(&s).ok());
            if let Some(Value::Object(top)) = read {
                let mut map = HashMap::new();
                if let Some(Value::Object(s)) = top.get("sessions") {
                    for (key, sid) in s {
                        if let Value::String(sid) = sid {
                            map.insert(sid.clone(), key.split('~').next().unwrap_or("").to_string());
                        }
                    }
                }
                self.sessions = map;
            }
            self.sessions_at = now();
        }
        let session = if path.contains("/subagents/") {
            basename(dirname(dirname(path))).to_string()
        } else {
            basename(path).split('.').next().unwrap_or("").to_string()
        };
        self.sessions.get(&session).cloned().unwrap_or_default()
    }

    /// The agent a chat runs as (its "agent:<name>" label), looked up once per chat.
    fn agent_of_thread(&mut self, thread: &str) -> String {
        if thread.is_empty() {
            return String::new();
        }
        if let Some(a) = self.agent_threads.get(thread) {
            return a.clone();
        }
        let mut agent = String::new();
        if let Ok(f) = File::open(at(THREADS)) {
            for raw in BufReader::new(f).split(b'\n').map_while(Result::ok) {
                for line in ulines(&raw) {
                    let line = String::from_utf8_lossy(line);
                    if !(line.contains(thread) && line.contains("\"labels\"")) {
                        continue;
                    }
                    let Ok(entry) = serde_json::from_str::<Value>(&line) else { continue };
                    if is_str(get(&entry, "thread_id"), thread) {
                        agent = match get(&entry, "labels") {
                            Some(Value::Array(ls)) => ls
                                .iter()
                                .filter_map(|l| l.as_str())
                                .find(|l| l.starts_with("agent:"))
                                .map(|l| after(l, 6).to_string())
                                .unwrap_or_default(),
                            _ => String::new(),
                        };
                    }
                }
            }
        }
        self.agent_threads.insert(thread.to_string(), agent.clone());
        agent
    }
}

/// Names in a folder in the order the folder lists them, hidden ones left out (what Python's glob "*" sees).
fn names(dir: &str) -> Vec<String> {
    let Ok(rd) = fs::read_dir(dir) else { return vec![] };
    rd.filter_map(|e| e.ok())
        .filter_map(|e| e.file_name().to_str().map(String::from))
        .filter(|n| !n.starts_with('.'))
        .collect()
}

/// glob(PROJECTS/*/*.jsonl) + glob(PROJECTS/*/*/subagents/*.jsonl), in the same order Python lists them
fn transcripts(projects: &str) -> Vec<String> {
    let dirs: Vec<String> = names(projects).into_iter().map(|n| format!("{projects}/{n}")).filter(|d| is_dir(d)).collect();
    let jsonl = |d: &str| -> Vec<String> {
        names(d).into_iter().filter(|n| n.ends_with(".jsonl")).map(|n| format!("{d}/{n}")).collect()
    };
    let mut out: Vec<String> = dirs.iter().flat_map(|d| jsonl(d)).collect();
    let mut subs = Vec::new();
    for d in &dirs {
        for e in names(d) {
            let sub = format!("{d}/{e}/subagents");
            if is_dir(&format!("{d}/{e}")) && is_dir(&sub) {
                subs.extend(jsonl(&sub));
            }
        }
    }
    if cfg().mode == Mode::Replay {
        // a recorded tree's folder order depends on the disk it sits on: --replay sorts (so does activity-golden)
        out.sort();
        subs.sort();
    }
    out.extend(subs);
    out
}

fn mtime(path: &str) -> Option<f64> {
    let m = fs::metadata(path).ok()?.modified().ok()?;
    Some(m.duration_since(UNIX_EPOCH).map(|d| d.as_secs_f64()).unwrap_or(0.0))
}

fn follow_transcripts() {
    let replay = cfg().mode == Mode::Replay;
    let projects = at(PROJECTS);
    let mut finder = Finder { sessions_at: 0.0, sessions: HashMap::new(), agent_threads: HashMap::new() };
    let mut tails: HashMap<String, Tail> = HashMap::new();
    for pass in 0.. {
        let t = now();
        for path in transcripts(&projects) {
            if !replay {
                match mtime(&path) {
                    Some(m) if t - m <= FRESH => {}
                    _ => continue,
                }
            }
            if let Some(tail) = tails.get_mut(&path) {
                tail.read();
                continue;
            }
            let sub = path.contains("/subagents/");
            let (mut who, mut job) = ("Mochi (Claude)".to_string(), String::new());
            let thread = finder.thread_of(&path);
            if !sub {
                let a = finder.agent_of_thread(&thread);
                if !a.is_empty() {
                    who = format!("Helper: {a}"); // a direct chat with one of Mochi's agents
                }
            } else {
                who = "Helper".into();
                let meta = fs::read_to_string(format!("{}.meta.json", &path[..path.len() - ".jsonl".len()]))
                    .ok()
                    .and_then(|s| serde_json::from_str::<Value>(&s).ok())
                    .filter(|m| m.is_object());
                if let Some(meta) = meta {
                    let kind = if has(&meta, "agentType") { gs(&meta, "agentType") } else { "helper".into() };
                    who = format!("Helper: {kind}");
                    job = if has(&meta, "description") { gs(&meta, "description") } else { String::new() };
                }
                log_helper("start", after(&who, 8), &job, &thread);
            }
            if !sub && !who.starts_with("Helper: ") {
                let a = agent_setting(&path);
                if !a.is_empty() {
                    who = format!("Helper: {a}");
                }
            }
            let pos = if replay {
                0
            } else {
                match fs::metadata(&path) {
                    Ok(m) => m.len(),
                    Err(_) => continue,
                }
            };
            tails.insert(path.clone(), Tail::new(path, who, thread, job, pos)); // starts at its end: only new steps
        }
        tails.retain(|p, _| std::path::Path::new(p).exists());
        if replay && pass >= 1 {
            return;
        }
        std::thread::sleep(Duration::from_secs(1));
    }
}

// ---------------------------------------------------------------- the voice side (journal)

#[derive(Default)]
struct Voice {
    call_task: String,
    last_step: String,
}

/// A tool Gemini ran on a call (logged by the voice gate with its detail).
fn voice_step(name: &str, detail: &str) -> (String, String, String) {
    if name.contains("memory") {
        return ("memory".into(), "Checking memory".into(), String::new());
    }
    if name.contains("search") || name.contains("answer") {
        return ("web".into(), format!("Searching the web: {}", short(detail, 60)), detail.into());
    }
    if name == "shell" {
        if detail.starts_with("mochi-phone") {
            return ("phone".into(), "Using your phone".into(), String::new());
        }
        let d = describe_command(detail, None);
        return (d.lane, d.text, detail.into());
    }
    ("voice".into(), capitalize(&name.replace('_', " ")), detail.into())
}

/// The voice side: a call is one task; each tool Gemini runs is a step, its result a line under it.
fn journal_line(v: &mut Voice, raw: &str) {
    let line = strip(raw);
    let r = re();
    const WHO: &str = "Mochi (voice)";
    if r.call_start.is_match(line) {
        CALL_ON.store(true, Ordering::SeqCst);
        v.call_task = emit("voice", WHO, "Call with Mochi", "", "", "task", "", "", "", None);
        v.last_step = emit("voice", WHO, "Call started", "", "", "step", "", &v.call_task, "", None);
    } else if r.call_end.is_match(line) {
        CALL_ON.store(false, Ordering::SeqCst);
        emit("voice", WHO, "Call ended", "", "", "step", "", &v.call_task, "", None);
        v.call_task.clear();
    } else if r.claude_back.is_match(line) {
        emit("brain", WHO, "Claude's answer reached the call", "", "", "step", "", &v.call_task, "", None);
    } else if let Some(m) = r.voice_tool.captures(line) {
        let (lane, text, detail) = voice_step(&m[1], &m[2]);
        v.last_step = emit(&lane, WHO, &text, "", &detail, "step", "", &v.call_task, "", None);
    } else if let Some(m) = r.voice_result.captures(line) {
        if !v.last_step.is_empty() {
            emit("voice", WHO, &m[2], "", "", "line", &v.last_step, &v.call_task, "", None);
        }
    }
}

mod journal {
    //! The openhuman unit's journal through libsystemd (what `journalctl -u openhuman -f -n 0 -o cat` printed).
    use std::os::raw::{c_char, c_int, c_void};

    #[link(name = "systemd")]
    extern "C" {
        fn sd_journal_open(ret: *mut *mut c_void, flags: c_int) -> c_int;
        fn sd_journal_close(j: *mut c_void);
        fn sd_journal_add_match(j: *mut c_void, data: *const c_void, size: usize) -> c_int;
        fn sd_journal_seek_tail(j: *mut c_void) -> c_int;
        fn sd_journal_previous(j: *mut c_void) -> c_int;
        fn sd_journal_next(j: *mut c_void) -> c_int;
        fn sd_journal_wait(j: *mut c_void, timeout_usec: u64) -> c_int;
        fn sd_journal_get_data(j: *mut c_void, field: *const c_char, data: *mut *const c_void, len: *mut usize) -> c_int;
    }

    const SD_JOURNAL_LOCAL_ONLY: c_int = 1;

    struct Handle(*mut c_void);
    impl Drop for Handle {
        fn drop(&mut self) {
            unsafe { sd_journal_close(self.0) }
        }
    }

    /// Calls `on_message` with each new MESSAGE of openhuman.service, from now on; returns only on an error.
    pub fn follow(unit_match: &str, mut on_message: impl FnMut(&[u8])) -> Result<(), String> {
        let mut raw = std::ptr::null_mut();
        let r = unsafe { sd_journal_open(&mut raw, SD_JOURNAL_LOCAL_ONLY) };
        if r < 0 {
            return Err(format!("sd_journal_open: {r}"));
        }
        let j = Handle(raw);
        let r = unsafe { sd_journal_add_match(j.0, unit_match.as_ptr() as *const c_void, unit_match.len()) };
        if r < 0 {
            return Err(format!("sd_journal_add_match: {r}"));
        }
        unsafe {
            if sd_journal_seek_tail(j.0) < 0 {
                return Err("sd_journal_seek_tail".into());
            }
            sd_journal_previous(j.0); // on the last entry: next() now gives only new ones (-n 0)
        }
        loop {
            let r = unsafe { sd_journal_next(j.0) };
            if r < 0 {
                return Err(format!("sd_journal_next: {r}"));
            }
            if r == 0 {
                let w = unsafe { sd_journal_wait(j.0, u64::MAX) };
                if w < 0 {
                    return Err(format!("sd_journal_wait: {w}"));
                }
                continue;
            }
            let mut data: *const c_void = std::ptr::null();
            let mut len: usize = 0;
            if unsafe { sd_journal_get_data(j.0, c"MESSAGE".as_ptr(), &mut data, &mut len) } >= 0 && !data.is_null() {
                let field = unsafe { std::slice::from_raw_parts(data as *const u8, len) };
                if let Some(msg) = field.strip_prefix(b"MESSAGE=") {
                    on_message(msg);
                }
            }
        }
    }
}

fn follow_journal() {
    let mut v = Voice::default();
    loop {
        let r = journal::follow("_SYSTEMD_UNIT=openhuman.service", |msg| {
            for line in ulines(msg) {
                journal_line(&mut v, &String::from_utf8_lossy(line));
            }
        });
        if let Err(e) = r {
            log(&format!("journal: {e}"));
        }
        std::thread::sleep(Duration::from_secs(5));
    }
}

fn replay_journal(root: &str) {
    let mut v = Voice::default();
    if let Ok(data) = fs::read(format!("{root}/journal.txt")) {
        for line in ulines(&data) {
            journal_line(&mut v, &String::from_utf8_lossy(line));
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (mode, root) = match args.as_slice() {
        [] => (Mode::Live, String::new()),
        [a] if a == "--dry-run" => (Mode::Dry, String::new()),
        [a, r] if a == "--replay" => (Mode::Replay, r.trim_end_matches('/').to_string()),
        _ => {
            eprintln!("usage: mochi-activity [--dry-run | --replay ROOT]");
            std::process::exit(2);
        }
    };
    let _ = CFG.set(Cfg { mode, root: root.clone() });
    match mode {
        Mode::Replay => {
            let helpers = at(HELPERS_LOG);
            let _ = fs::remove_file(&helpers);
            replay_journal(&root);
            follow_transcripts();
            if let Ok(s) = fs::read_to_string(&helpers) {
                for l in s.lines() {
                    out_line(&format!("H {l}"));
                }
            }
            let _ = fs::remove_file(&helpers);
        }
        _ => {
            if mode == Mode::Live {
                std::thread::spawn(sender);
            }
            std::thread::spawn(follow_journal);
            log("following Mochi's transcripts and journal");
            follow_transcripts();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn init() {
        let _ = CFG.set(Cfg { mode: Mode::Replay, root: String::new() });
    }

    #[test]
    fn python_text_rules() {
        assert_eq!(splitlines("a\r\nb\rc\n\nd\u{2028}e\n"), vec!["a", "b", "c", "", "d", "e"]);
        assert_eq!(splitlines(""), Vec::<&str>::new());
        assert_eq!(short("  one   two\tthree ", 60), "one two three");
        assert_eq!(short("abcdef", 4), "abc…");
        assert_eq!(short("ünïcödé", 4), "ünï…");
        assert_eq!(thousands(1234567), "1,234,567");
        assert_eq!(thousands(999), "999");
        assert_eq!(capitalize("web SEARCH tool"), "Web search tool");
        assert_eq!(base("/a/b/c.txt"), "c.txt");
        assert_eq!(base("/a/b/"), "b");
        assert_eq!(base("/"), "/");
        assert_eq!(base(""), "");
        assert_eq!(dirname("/a/b/c"), "/a/b");
        assert_eq!(strip("\x1c x \u{a0}"), "x");
    }

    #[test]
    fn python_values() {
        let v: Value = serde_json::from_str(r#"{"b": [1, 2.5, null, true], "a": "it's", "q": "say \"hi\"", "f": 1.0, "e": 1e20}"#).unwrap();
        assert_eq!(py_str(&v), r#"{'b': [1, 2.5, None, True], 'a': "it's", 'q': 'say "hi"', 'f': 1.0, 'e': 1e+20}"#);
        assert_eq!(py_float(0.0001), "0.0001");
        assert_eq!(py_float(0.00001), "1e-05");
        assert_eq!(py_float(-0.0), "-0.0");
    }

    #[test]
    fn masking() {
        init();
        assert_eq!(clean_detail("curl -H 'Authorization: Bearer abc.def' x"), "curl -H 'Authorization: Bearer ••• x");
        assert_eq!(clean_detail("TOKEN=abc ./run"), "TOKEN=••• ./run");
        assert_eq!(clean_detail("api_key: \"zzz\" next"), "api_key: ••• next");
        // already masked stays as it is, and a later secret in the same word is still found (Python's look-ahead)
        assert_eq!(clean_detail("token=•••,password=abc"), "token=•••,password=•••");
        assert_eq!(clean_detail("keyboard=qwerty"), "keyboard=•••");
        assert_eq!(mask("line one\npassword = hunter2\n", 1000), "line one\npassword = •••\n");
    }

    #[test]
    fn json_like_python() {
        let mut s = String::new();
        jstr("a\"b\\c\nd\u{7f}é😀\x01", &mut s);
        // Python's json.dumps default (ensure_ascii): non-ASCII as \u escapes, astral characters as surrogate pairs
        assert_eq!(s, r#""a\"b\\c\nd\u007f\u00e9\ud83d\ude00\u0001""#);
        assert_eq!(jobj(&[("a", J::S("x")), ("n", J::I(3))]), r#"{"a": "x", "n": 3}"#);
    }

    #[test]
    fn commands_in_plain_words() {
        init();
        let d = describe_command("cd ~/OpenHuman/projects/github/site && FOO=1 npm run build", None);
        assert_eq!((d.lane.as_str(), d.text.as_str(), d.repo.as_str()), ("server", "Running npm run", "site"));
        let d = describe_command("mochi-git pr owner/repo --title x", None);
        assert_eq!((d.text.as_str(), d.repo.as_str()), ("GitHub: pr owner/repo", ""));
        let d = describe_command("python3 -m pytest -q", None);
        assert_eq!(d.text, "Running the tests");
        let d = describe_command("   ", None);
        assert_eq!(d.text, "Running a command");
    }

    #[test]
    fn december_words() {
        init();
        let t = "Current Date & Time: today\n\n[voice recap]\n\n(Asked on a voice call at 10:00) find the  file\n<memory-context>x";
        assert_eq!(own_words(t), "find the  file");
    }
}
