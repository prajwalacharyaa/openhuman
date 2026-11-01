use super::*;

fn ad(y: i32, m: u32, d: u32) -> NaiveDate {
    NaiveDate::from_ymd_opt(y, m, d).unwrap()
}

fn bs(year: i32, month: u32, day: u32) -> BsDate {
    BsDate { year, month, day }
}

#[test]
fn converts_known_dates() {
    // anchor
    assert_eq!(from_gregorian(ad(2023, 4, 14)), Some(bs(2080, 1, 1)));
    // Kathmandu panchang: 1 Nov 2026 = 15 Kartik 2083, 16 Nov = 30 Kartik, 17 Nov = 1 Mangsir
    assert_eq!(from_gregorian(ad(2026, 11, 1)), Some(bs(2083, 7, 15)));
    assert_eq!(from_gregorian(ad(2026, 11, 16)), Some(bs(2083, 7, 30)));
    assert_eq!(from_gregorian(ad(2026, 11, 17)), Some(bs(2083, 8, 1)));
}

#[test]
fn every_table_year_has_a_plausible_length() {
    // 364 occurs once (2096 BS) in the source table; far-future BS tables differ between publishers.
    for (i, months) in MONTH_DAYS.iter().enumerate() {
        let days: u32 = months.iter().map(|&d| u32::from(d)).sum();
        assert!((364..=366).contains(&days), "{} BS has {days} days", FIRST_YEAR + i as i32);
    }
}

#[test]
fn outside_the_table_is_none() {
    assert_eq!(from_gregorian(ad(2023, 4, 13)), None);
    assert_eq!(from_gregorian(ad(2050, 1, 1)), None);
}

#[test]
fn formats_and_only_stamps_nepal() {
    assert_eq!(bs(2083, 7, 15).to_string(), "15 Kartik 2083");
    assert_eq!(
        stamp_suffix("Asia/Kathmandu", ad(2026, 11, 1)),
        ", Nepali date (Bikram Sambat): 15 Kartik 2083"
    );
    assert_eq!(stamp_suffix("Europe/London", ad(2026, 11, 1)), "");
}
