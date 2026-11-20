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
    // the room (2026-11-20): read meetings and send the draft December said yes to; never write a brief or a PDF
    assert!(voice_may_run("mochi-room"));
    assert!(voice_may_run("mochi-room who cafe"));
    assert!(voice_may_run("mochi-room show 2"));
    assert!(voice_may_run("mochi-room send"));
    assert!(!voice_may_run("mochi-room draft \"should I hire\""), "the brief is Claude's (room skill)");
    assert!(!voice_may_run("mochi-room pdf"));
    assert!(!voice_may_run("mochi-books summary-set alice book"));
    assert!(!voice_may_run("mochi-books"), "nothing to do is not a read either");
}

#[test]
fn reading_goes_on_part_after_part_until_the_end() {
    let more = "Animal Farm: Chapter I, continuing.\n\nRead this part aloud...";
    assert_eq!(next_part("mochi-books read \"Animal Farm\" --chapter 1 --start", more).as_deref(),
        Some("mochi-books read \"Animal Farm\""));
    assert_eq!(next_part("mochi-books read 20261114-c0e9a4", more).as_deref(), Some("mochi-books read 20261114-c0e9a4"));
    assert_eq!(next_part("mochi-books read 20261114-c0e9a4", "...(End of the book.)"), None);
    assert_eq!(next_part("mochi-books list", more), None);
    assert_eq!(next_part("mochi-ask --thread t-1 --read", "Read this part aloud... run mochi-ask --read again").as_deref(),
        Some("mochi-ask --read"));
    assert_eq!(next_part("mochi-ask --read", "... stop: it's the end."), None);
    reading_set("t1", Some("mochi-books read x".into()));
    assert_eq!(reading_next("t1").as_deref(), Some("mochi-books read x"));
    reading_stop("t1");
    assert_eq!(reading_next("t1"), None);
}

#[test]
fn a_search_for_a_book_in_the_library_goes_to_the_library() {
    let cat = r#"{"books":[{"title":"Animal Farm"},{"title":"Man's Search For Meaning"},{"title":"It"}]}"#;
    assert_eq!(library_book("summary of Animal Farm by George Orwell", cat).as_deref(), Some("Animal Farm"));
    assert_eq!(library_book("man's search for meaning chapter titles", cat).as_deref(), Some("Man's Search For Meaning"));
    assert_eq!(library_book("weather in Pokhara", cat), None);
    assert_eq!(library_book("is it raining", cat), None, "a two-letter title never matches a sentence");
    assert_eq!(library_book("anything", "not json"), None);
}


#[test]
fn a_guest_on_the_phone_gets_no_tool_of_decembers() {
    let shell = |command: &str| serde_json::json!({ "command": command });
    assert!(guest_may("shell", &shell("mochi-ask \"How does a restaurant claim its page on JiguBites?\"")));
    assert!(guest_may("web_search_tool", &serde_json::json!({ "query": "jigubites" })));
    assert!(guest_may("shell", &shell("mochi-guest \"Prabesh\" \"JiguBites for his restaurant\"")));
    for command in ["mochi-phone contacts", "cat /home/openhuman/OpenHuman/workspace/trading-brief.md",
                    "mochi-mail list", "mochi-remind --in 2m \"x\"", "mochi-ask --result; mochi-phone screen inbox"] {
        assert!(!guest_may("shell", &shell(command)), "{command}");
    }
    assert!(!guest_may("memory", &serde_json::json!({ "query": "December's shares" })));
    assert!(voice_may_run("mochi-guest \"Prabesh\" \"JiguBites\""));
}
