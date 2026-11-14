use super::*;

#[test]
fn a_call_runs_only_mochis_helpers() {
    assert!(voice_may_run("mochi-ask \"Check whether the branch is merged\""));
    assert!(voice_may_run("cat /home/openhuman/OpenHuman/workspace/server-status.md"));
    assert!(voice_may_run("mochi-phone contacts \"mum\" | cat"));
    assert!(voice_may_run("/usr/local/bin/mochi-remind list"));
    assert!(!voice_may_run("pip install graphifyy && graphify install"));
    assert!(!voice_may_run("mochi-ask hi; rm -rf ~"));
    assert!(!voice_may_run("cat $(which python3)"));
    assert!(!voice_may_run("git -C ~/OpenHuman/projects/github/portfolio-site status"));
    assert!(voice_may_run("mochi-ask \"Compare R&D costs; then check A | B\""), "separators inside quotes are text");
    assert!(!voice_may_run("cat notes > ~/.bashrc"), "no writing files");
    assert!(!voice_may_run("mochi-ask \"$(cat ~/.ssh/id_ed25519)\""));
    assert!(voice_may_run("mochi-ask \"Is NEPSE above $2,000? It's December's question\""));
    assert!(!voice_may_run(""), "nothing to run is not a helper either");
}

#[test]
fn a_calls_mochi_ask_names_the_calls_own_chat() {
    let thread = Some("thread-559f6859-55f8-4b52-91af-b8ab53ac2198");
    assert_eq!(
        with_call_thread("mochi-ask \"Samir, what is my portfolio holding?\"", thread).as_deref(),
        Some("mochi-ask --thread thread-559f6859-55f8-4b52-91af-b8ab53ac2198 \"Samir, what is my portfolio holding?\"")
    );
    assert_eq!(
        with_call_thread("mochi-ask --result", thread).as_deref(),
        Some("mochi-ask --thread thread-559f6859-55f8-4b52-91af-b8ab53ac2198 --result")
    );
    assert!(voice_may_run(&with_call_thread("mochi-ask --cancel", thread).unwrap()), "still a voice helper");
    assert_eq!(with_call_thread("mochi-ask --thread x --result", thread), None, "already named");
    assert_eq!(with_call_thread("mochi-phone screen trading", thread), None);
    assert_eq!(with_call_thread("mochi-asking \"x\"", thread), None);
    assert_eq!(with_call_thread("mochi-ask \"x\"", None), None, "no chat known: the helper picks as before");
    assert_eq!(with_call_thread("mochi-ask \"x\"", Some("a b; rm -rf")), None, "never an id that isn't one");
}

#[test]
fn a_calls_cat_reads_only_the_workspace() {
    assert!(voice_may_run("cat /home/openhuman/OpenHuman/workspace/youtube/report.md"));
    assert!(voice_may_run("mochi-phone contacts \"mum\" | cat"), "stdin is fine");
    assert!(!voice_may_run("cat /home/openhuman/OpenHuman/claude-auth.conf"), "keys live outside the workspace");
    assert!(!voice_may_run("cat /proc/1/status"));
    assert!(!voice_may_run("cat /home/openhuman/OpenHuman/workspace/../core-auth.conf"));
    assert!(!voice_may_run("cat ~/OpenHuman/core-auth.conf"));
    assert!(!voice_may_run("cat /home/openhuman/OpenHuman/workspace/*"));
    assert!(!voice_may_run("cat report.md"), "relative paths are refused");
}

#[cfg(unix)]
#[test]
fn a_workspace_link_out_of_it_is_refused() {
    let dir = tempfile::tempdir().unwrap();
    let outside = dir.path().join("secret");
    std::fs::write(&outside, "x").unwrap();
    let root = workspace_root();
    // only meaningful where the default workspace exists (the server); elsewhere the lexical check covers it
    if std::fs::create_dir_all(&root).is_ok() {
        let link = root.join(format!("link-test-{}", std::process::id()));
        if std::os::unix::fs::symlink(&outside, &link).is_ok() {
            assert!(!voice_may_run(&format!("cat {}", link.display())));
            let _ = std::fs::remove_file(&link);
        }
    }
}

#[test]
fn a_voice_step_shows_its_command_but_never_phone_details() {
    assert_eq!(step_detail("shell", &serde_json::json!({"command": "mochi-ask \"check   the repo\""})), "mochi-ask \"check the repo\"");
    assert_eq!(step_detail("shell", &serde_json::json!({"command": "mochi-phone contacts \"mum\""})), "mochi-phone");
    assert_eq!(step_detail("web_search_tool", &serde_json::json!({"query": "NEPSE today"})), "NEPSE today");
    assert_eq!(step_detail("memory_search", &serde_json::json!({})), "memory_search");
}

#[test]
fn a_voice_result_is_one_line_and_never_phone_data() {
    assert_eq!(result_line("mochi-ask \"x\"", "Sent. If you haven't said so\nmore", false), "Sent. If you haven't said so");
    assert_eq!(result_line("mochi-phone", "Mum: 98…", false), "done");
    assert_eq!(result_line("memory_search", "", true), "failed: done");
}

#[test]
fn repeated_and_bursty_searches_are_steered_to_claude() {
    let t0 = std::time::Instant::now();
    let q = "voice-shell-test unique query about a person";
    assert_eq!(search_gate(q, t0), Ok(false));
    assert!(search_gate(&q.to_uppercase(), t0 + std::time::Duration::from_secs(7)).is_err(), "same search, reused");
    assert_eq!(search_gate("voice-shell-test second query", t0 + std::time::Duration::from_secs(10)), Ok(false));
    assert_eq!(search_gate("voice-shell-test third query", t0 + std::time::Duration::from_secs(20)), Ok(true));
}

#[test]
fn a_call_may_read_books_but_not_change_the_library() {
    assert!(voice_may_run("mochi-books read \"alice\" --chapter 3"));
    assert!(voice_may_run("mochi-books search all \"the caterpillar\""));
    assert!(voice_may_run("mochi-books summary alice 2"));
    assert!(!voice_may_run("mochi-books delete alice"), "deleting is December's tap");
    assert!(!voice_may_run("mochi-books import /tmp/x.pdf"));
    assert!(!voice_may_run("mochi-books summary-set alice book"));
    assert!(!voice_may_run("mochi-books"), "nothing to do is not a read either");
}
