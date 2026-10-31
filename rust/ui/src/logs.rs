use chrono::TimeZone;
use regex::Regex;
use std::{
    fs,
    io::{self, Read, Seek, SeekFrom},
    path::Path,
    sync::OnceLock,
};

pub fn read(game_dir: &Path, home: &str) -> io::Result<Vec<String>> {
    let direct = game_dir.join("latest-game.log");
    let standard = game_dir.join("logs/latest.log");
    let path = if direct.is_file() {
        direct
    } else if standard.is_file() {
        standard
    } else {
        return Ok(Vec::new());
    };
    let mut file = fs::File::open(path)?;
    let len = file.metadata()?.len();
    let start = len.saturating_sub(1 << 20);
    file.seek(SeekFrom::Start(start))?;
    let mut bytes = Vec::new();
    file.take(1 << 20).read_to_end(&mut bytes)?;
    if start > 0 {
        if let Some(pos) = bytes.iter().position(|b| *b == b'\n') {
            bytes.drain(..=pos);
        }
    }
    let text = redact(&readable(&String::from_utf8_lossy(&bytes)), home);
    let lines: Vec<_> = text.lines().collect();
    Ok(lines
        .iter()
        .skip(lines.len().saturating_sub(3000))
        .map(|line| (*line).to_owned())
        .collect())
}

fn readable(text: &str) -> String {
    if !text.contains("<log4j:Event") {
        return text.to_owned();
    }
    static EVENT: OnceLock<Regex> = OnceLock::new();
    static ATTR: OnceLock<Regex> = OnceLock::new();
    static MESSAGE: OnceLock<Regex> = OnceLock::new();
    static THROWABLE: OnceLock<Regex> = OnceLock::new();
    static BLANK: OnceLock<Regex> = OnceLock::new();
    let event = EVENT.get_or_init(|| {
        Regex::new(r"(?s)[ \t]*<log4j:Event\s([^>]*)>(.*?)</log4j:Event>").unwrap()
    });
    let attr = ATTR.get_or_init(|| Regex::new(r#"(\w+)="([^"]*)""#).unwrap());
    let message =
        MESSAGE.get_or_init(|| Regex::new(r"(?s)<log4j:Message><!\[CDATA\[(.*?)\]\]>").unwrap());
    let throwable = THROWABLE
        .get_or_init(|| Regex::new(r"(?s)<log4j:Throwable><!\[CDATA\[(.*?)\]\]>").unwrap());
    let start = match (text.find("<log4j:Event"), text.find("</log4j:Event>")) {
        (Some(open), Some(close)) if close < open => close + "</log4j:Event>".len(),
        _ => 0,
    };
    let output = event.replace_all(&text[start..], |c: &regex::Captures<'_>| {
        let attrs: std::collections::HashMap<_, _> = attr
            .captures_iter(&c[1])
            .map(|v| (v[1].to_owned(), v[2].to_owned()))
            .collect();
        let time = attrs
            .get("timestamp")
            .and_then(|v| v.parse::<i64>().ok())
            .and_then(|v| chrono::Local.timestamp_millis_opt(v).single())
            .map(|v| v.format("%H:%M:%S").to_string())
            .unwrap_or_default();
        let body = c[2].replace("]]><![CDATA[", "");
        let content = message
            .captures(&body)
            .map(|m| m[1].to_owned())
            .unwrap_or_default();
        let stack = throwable
            .captures(&body)
            .map(|m| format!("\n{}", m[1].trim_end()))
            .unwrap_or_default();
        format!(
            "[{time}] [{}/{}]: {content}{stack}",
            attrs.get("thread").map(String::as_str).unwrap_or(""),
            attrs.get("level").map(String::as_str).unwrap_or("")
        )
    });
    BLANK
        .get_or_init(|| Regex::new(r"\n\s*\n").unwrap())
        .replace_all(&output, "\n")
        .into_owned()
}

fn redact(text: &str, home: &str) -> String {
    static JWT: OnceLock<Regex> = OnceLock::new();
    static TOKEN: OnceLock<Regex> = OnceLock::new();
    let jwt = JWT.get_or_init(|| {
        Regex::new(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}").unwrap()
    });
    let token = TOKEN.get_or_init(|| Regex::new(r"(--accessToken\s+)\S+").unwrap());
    let mut output = token
        .replace_all(&jwt.replace_all(text, "<token>"), "${1}<token>")
        .into_owned();
    if home.len() > 3 {
        for path in [home.to_string(), home.replace('\\', "/")] {
            if let Ok(re) = Regex::new(&format!("(?i){}", regex::escape(&path))) {
                output = re.replace_all(&output, "~").into_owned();
            }
        }
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn redacts_token_and_home() {
        let output = redact(
            "--accessToken secret C:\\Users\\Example\\file eyJabcdefgh.abcdefgh.abcdefgh",
            "C:\\Users\\Example",
        );
        assert!(!output.contains("secret"));
        assert!(!output.contains("Example"));
        assert!(!output.contains("eyJabcdefgh"));
    }
    #[test]
    fn caps_log_lines_and_falls_back_to_latest() {
        let dir = std::env::temp_dir().join(format!("aw-ui-log-test-{}", std::process::id()));
        fs::create_dir_all(dir.join("logs")).unwrap();
        fs::write(
            dir.join("logs/latest.log"),
            (0..3100).map(|i| format!("line{i}\n")).collect::<String>(),
        )
        .unwrap();
        let lines = read(&dir, "").unwrap();
        assert_eq!(lines.len(), 3000);
        assert_eq!(lines[0], "line100");
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn renders_log4j_events() {
        let text = "<log4j:Event thread=\"main\" level=\"INFO\"><log4j:Message><![CDATA[Hello]]></log4j:Message></log4j:Event>";
        assert_eq!(readable(text), "[] [main/INFO]: Hello");
    }
}
