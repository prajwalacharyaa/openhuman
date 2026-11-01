//! Bikram Sambat (BS), the Nepali calendar, for the "now" stamp.
//!
//! BS month lengths are published per year rather than computed, so the
//! conversion walks a table from a fixed anchor: 1 Baisakh 2080 BS is
//! 14 April 2023. The table (2080–2099 BS, mid-2023 to early 2043) comes from
//! the `nepali-datetime` package and was cross-checked against a Kathmandu
//! panchang (1 November 2026 = 15 Kartik 2083). Outside the table the
//! conversion returns `None` and the stamp simply has no BS date.

use chrono::NaiveDate;

/// The first BS year in [`MONTH_DAYS`].
const FIRST_YEAR: i32 = 2080;

/// Days in each month (Baisakh … Chaitra) of the years from [`FIRST_YEAR`].
const MONTH_DAYS: [[u8; 12]; 20] = [
    [31, 32, 31, 32, 31, 30, 30, 30, 29, 29, 30, 30], // 2080
    [31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 29, 31], // 2081
    [31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30], // 2082
    [31, 31, 32, 31, 31, 31, 30, 29, 30, 29, 30, 30], // 2083
    [31, 31, 32, 31, 31, 30, 30, 30, 29, 30, 30, 30], // 2084
    [31, 32, 31, 32, 30, 31, 30, 30, 29, 30, 30, 30], // 2085
    [30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 30, 30], // 2086
    [31, 31, 32, 31, 31, 31, 30, 29, 30, 30, 30, 30], // 2087
    [30, 31, 32, 32, 30, 31, 30, 30, 29, 30, 30, 30], // 2088
    [30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 30, 30], // 2089
    [30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 30, 30], // 2090
    [31, 31, 32, 31, 31, 31, 30, 30, 29, 30, 30, 30], // 2091
    [30, 31, 32, 32, 31, 30, 30, 30, 29, 30, 30, 30], // 2092
    [30, 32, 31, 32, 31, 30, 30, 30, 29, 30, 30, 30], // 2093
    [31, 31, 32, 31, 31, 30, 30, 30, 29, 30, 30, 30], // 2094
    [31, 31, 32, 31, 31, 31, 30, 29, 30, 30, 30, 30], // 2095
    [30, 31, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30], // 2096
    [31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 30, 30], // 2097
    [31, 31, 32, 31, 31, 31, 29, 30, 29, 30, 29, 31], // 2098
    [31, 31, 32, 31, 31, 31, 30, 29, 29, 30, 30, 30], // 2099
];

/// BS month names, Baisakh first.
const MONTH_NAMES: [&str; 12] = [
    "Baisakh", "Jestha", "Asar", "Shrawan", "Bhadra", "Ashwin", "Kartik", "Mangsir", "Poush",
    "Magh", "Falgun", "Chaitra",
];

/// IANA names of Nepal's zone; the stamp carries a BS date only there.
const NEPAL_ZONES: [&str; 2] = ["Asia/Kathmandu", "Asia/Katmandu"];

/// A Bikram Sambat date.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BsDate {
    pub year: i32,
    /// 1 = Baisakh … 12 = Chaitra.
    pub month: u32,
    pub day: u32,
}

impl BsDate {
    /// The month's name, e.g. `Kartik`.
    #[must_use]
    pub fn month_name(&self) -> &'static str {
        MONTH_NAMES[(self.month - 1) as usize]
    }
}

impl std::fmt::Display for BsDate {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{} {} {}", self.day, self.month_name(), self.year)
    }
}

/// The BS date of a Gregorian date, or `None` outside the table.
#[must_use]
pub fn from_gregorian(date: NaiveDate) -> Option<BsDate> {
    let anchor = NaiveDate::from_ymd_opt(2023, 4, 14)?;
    let mut days = (date - anchor).num_days();
    if days < 0 {
        return None;
    }
    for (offset, months) in MONTH_DAYS.iter().enumerate() {
        for (month, &len) in months.iter().enumerate() {
            let len = i64::from(len);
            if days < len {
                return Some(BsDate {
                    year: FIRST_YEAR + offset as i32,
                    month: month as u32 + 1,
                    day: days as u32 + 1,
                });
            }
            days -= len;
        }
    }
    None
}

/// `, Nepali date (Bikram Sambat): 15 Kartik 2083` for a local date in
/// Nepal's zone; empty anywhere else or outside the table.
#[must_use]
pub fn stamp_suffix(zone: &str, local_date: NaiveDate) -> String {
    if !NEPAL_ZONES.contains(&zone) {
        return String::new();
    }
    from_gregorian(local_date)
        .map(|bs| format!(", Nepali date (Bikram Sambat): {bs}"))
        .unwrap_or_default()
}

#[cfg(test)]
#[path = "bikram_sambat_tests.rs"]
mod tests;
