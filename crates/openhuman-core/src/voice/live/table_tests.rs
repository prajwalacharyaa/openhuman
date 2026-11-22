use super::*;

#[test]
fn at_a_table_mochi_answers_its_name_or_a_follow_up() {
    let now = Instant::now();
    assert!(addressed("Mochi, what's the weather?", None, now));
    assert!(addressed("okay so mochie can you check", None, now));
    assert!(addressed("मोची, आज के छ?", None, now));
    assert!(!addressed("did you eat already", None, now));
    assert!(!addressed("I told the room, and then Mochi said", None, now), "the name late in the sentence is talk about Mochi");
    assert!(addressed("and what about the price", Some(now - Duration::from_secs(5)), now), "a follow-up");
    assert!(!addressed("and what about the price", Some(now - Duration::from_secs(40)), now));
}

#[test]
fn a_reply_to_table_talk_is_held_back_until_december_talks_to_mochi() {
    let mut t = Table::default();
    let now = Instant::now();
    assert_eq!(t.user_said("did you eat", true, now), None, "off: nothing changes");
    t.set(true);
    assert_eq!(t.user_said("did you eat", true, now), Some(true), "table talk: hold the reply");
    t.mochi_spoke(now);
    assert_eq!(t.user_said("yes I did", true, now + Duration::from_secs(3)), None, "a held reply is no follow-up");
    assert_eq!(t.user_said("Mochi, set a timer", false, now + Duration::from_secs(9)), Some(false));
    t.mochi_spoke(now + Duration::from_secs(10));
    assert_eq!(t.user_said("for ten minutes", true, now + Duration::from_secs(12)), None, "follow-up keeps it on");
    t.set(false);
    assert!(!t.hush);
}
