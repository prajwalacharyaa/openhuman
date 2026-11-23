use super::*;

#[test]
fn times_answers_and_counts_missed_pings() {
    let t = Instant::now();
    let mut line = Line::default();
    assert!(line.summary().is_none());
    assert!(line.ping(t).is_none());
    assert!(line.pong(t + Duration::from_millis(350)).is_none());
    assert!(line.ping(t + EVERY).is_none());
    // no answer before the next ping: missed
    assert!(line.ping(t + EVERY * 2).is_some());
    assert!(line.pong(t + EVERY * 2 + Duration::from_millis(2000)).unwrap().contains("2000 ms"));
    // a stray answer with no ping out is ignored
    assert!(line.pong(t + EVERY * 3).is_none());
    assert_eq!(
        line.summary().unwrap(),
        "the phone's line: 2 pings answered (middle 350 ms, worst 2000 ms), 1 unanswered"
    );
}
