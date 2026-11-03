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
