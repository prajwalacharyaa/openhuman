use super::*;

#[test]
fn warming_is_well_inside_the_embedders_idle_limit_and_can_be_turned_off() {
    assert!(WARM_EVERY < Duration::from_secs(600), "the embedder unloads after 10 idle minutes");
    // one test touches the variable, so no other test races it
    std::env::set_var("MOCHI_EMBEDDER_HEALTH", "");
    assert_eq!(health_url(), None);
    std::env::set_var("MOCHI_EMBEDDER_HEALTH", "http://127.0.0.1:9/health");
    assert_eq!(health_url().as_deref(), Some("http://127.0.0.1:9/health"));
    std::env::remove_var("MOCHI_EMBEDDER_HEALTH");
    assert_eq!(health_url().as_deref(), Some(DEFAULT_HEALTH));
}
