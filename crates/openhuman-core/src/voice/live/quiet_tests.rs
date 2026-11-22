use super::*;

#[test]
fn a_phone_step_that_worked_is_not_talked_about() {
    assert!(is_screen_step("shell", "mochi-phone swipe up"));
    assert!(is_screen_step("shell", "mochi-phone tap \"Reels\""));
    assert!(!is_screen_step("shell", "mochi-phone calendar today"), "a reading is answered out loud");
    assert!(!is_screen_step("shell", "mochi-phone learn \"pause\""), "learning says it's watching");
    assert!(!is_screen_step("shell", "mochi-ask \"open the repo\""));
    let mut q = Quiet::default();
    q.tool_call("1", "shell", "mochi-phone swipe up");
    assert!(q.tool_finished("1", false));
    assert!(q.on, "Done. is held back");
    q.end();
    assert!(!q.on, "December spoke: the next reply is heard");
    q.tool_call("2", "shell", "mochi-phone tap \"Sned\"");
    assert!(!q.tool_finished("2", true));
    assert!(!q.on, "a failed step is explained out loud");
    q.tool_call("3", "shell", "mochi-phone home");
    q.tool_finished("3", false);
    q.tool_call("4", "shell", "cat server-status.md");
    assert!(!q.on, "another tool: its answer is spoken");
}
