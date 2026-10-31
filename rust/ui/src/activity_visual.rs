use super::*;
use chrono::{Datelike, TimeZone};
use egui::{Align2, FontId, Pos2, Rect, Sense, Stroke};
const SHORT: [&str; 12] = [
    "янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек",
];
fn label(ui: &egui::Ui, pos: Pos2, value: &str, size: f32, bold: bool, color: Color32) {
    raw_label(ui, pos, &translations::text(value), size, bold, color);
}
fn raw_label(ui: &egui::Ui, pos: Pos2, value: &str, size: f32, bold: bool, color: Color32) {
    ui.painter().text(
        pos,
        Align2::LEFT_TOP,
        value,
        FontId::new(
            size,
            if bold {
                FontFamily::Name("aw-semibold".into())
            } else {
                FontFamily::Proportional
            },
        ),
        color,
    );
}
fn duration(ms: i64) -> String {
    let minutes = ms.max(0) / 60_000;
    let hours = minutes / 60;
    if hours == 0 {
        format!("{minutes} мин")
    } else if minutes % 60 == 0 {
        format!("{hours} ч")
    } else {
        format!("{hours} ч {} мин", minutes % 60)
    }
}
fn days(value: usize) -> String {
    let word = if value % 100 >= 11 && value % 100 <= 14 {
        "дней"
    } else {
        match value % 10 {
            1 => "день",
            2..=4 => "дня",
            _ => "дней",
        }
    };
    format!("{value} {word}")
}
fn mix(base: Color32, accent: Color32, t: f32) -> Color32 {
    let c = |a: u8, b: u8| (a as f32 * (1.0 - t) + b as f32 * t).round() as u8;
    Color32::from_rgb(
        c(base.r(), accent.r()),
        c(base.g(), accent.g()),
        c(base.b(), accent.b()),
    )
}
fn region(o: Pos2, x: f32, y: f32, w: f32, h: f32) -> Rect {
    Rect::from_min_size(o + Vec2::new(x, y), Vec2::new(w, h))
}
impl App {
    fn stat_icon(&self, ui: &egui::Ui, name: &str, rect: Rect, tint: Color32) {
        ui.painter().rect_filled(rect, 4.0, self.palette.high);
        if let Some(texture) = self.nav_icons.get(name) {
            ui.painter().image(
                texture.id(),
                Rect::from_center_size(rect.center(), Vec2::splat(rect.width() * 0.54)),
                Rect::from_min_max(Pos2::ZERO, egui::pos2(1.0, 1.0)),
                tint,
            );
        }
    }
    pub(super) fn exact_activity(&mut self, ui: &mut egui::Ui) {
        let stats = self.activity.clone();
        let stats = stats.as_ref();
        let p = self.palette;
        let today = chrono::Local::now().date_naive();
        let gap = 16.0;
        let width = ui.available_width().min(1298.0);
        let cols = if width < 650.0 { 2 } else { 4 };
        let summary_h = if cols == 2 { 248.0 } else { 116.0 };
        let narrow = ui.available_width() + 48.0 < 760.0;
        let paired = ui.available_width() + 48.0 >= 1440.0;
        let versions = stats.map(|s| s.versions.len()).unwrap_or(0);
        let servers = stats.map(|s| s.servers.len()).unwrap_or(0);
        let version_h = 84.0 + versions as f32 * 42.0;
        let server_h = 84.0 + servers as f32 * 42.0;
        let show_versions = self.activity_target.is_none();
        let left_h = if show_versions {
            version_h + gap + server_h
        } else {
            server_h
        };
        let recent_count = stats.map(|s| s.recent.len()).unwrap_or(0);
        let recent_rows = if narrow {
            12
        } else {
            ((left_h - 80.0) / 38.0).floor().clamp(3.0, 12.0) as usize
        };
        let recent_h = 80.0 + recent_count.min(recent_rows) as f32 * 38.0;
        let details_h = if narrow {
            left_h + gap + recent_h
        } else {
            left_h.max(recent_h)
        };
        let imported = stats.is_some_and(|s| s.imported_ms > 0);
        let import_h = if imported { 34.0 } else { 0.0 };
        let charts_h = if paired { 242.0 } else { 216.0 + gap + 242.0 };
        let height = 34.0 + gap + summary_h + gap + import_h + charts_h + gap + details_h;
        egui::ScrollArea::vertical().auto_shrink([false,false]).show(ui,|ui|{
   let(canvas,_)=ui.allocate_exact_size(Vec2::new(ui.available_width(),height),Sense::hover());let o=canvas.min+Vec2::new((canvas.width()-width)/2.0,0.0);
   label(ui,o+Vec2::new(6.0,0.0),if self.activity_target.is_some(){"Статистика сборки"}else{"Активность"},26.0,true,p.text);
   let values=[("Всего в игре",stats.map(|s|duration(s.total_ms)).unwrap_or_else(||"…".into()),"clock",p.accent),("Эта неделя",stats.map(|s|duration(s.week_ms)).unwrap_or_else(||"…".into()),"calendar",Color32::from_rgb(148,189,213)),("Серия",stats.map(|s|days(s.streak)).unwrap_or_else(||"…".into()),"flame",Color32::from_rgb(238,177,109)),("Рекорд",stats.map(|s|if s.longest_ms>0{duration(s.longest_ms)}else{"—".into()}).unwrap_or_else(||"…".into()),"trophy",Color32::from_rgb(85,213,142))];
   let cw=(width-(cols-1)as f32*gap)/cols as f32;
   for(i,(name,value,icon,color))in values.iter().enumerate(){let r=region(o,(i%cols)as f32*(cw+gap),50.0+(i/cols)as f32*132.0,cw,116.0);ui.painter().rect_filled(r,8.0,p.panel);self.stat_icon(ui,icon,Rect::from_min_size(r.min+Vec2::splat(18.0),Vec2::splat(30.0)),*color);label(ui,r.min+Vec2::new(58.0,25.0),name,11.0,true,p.muted);let size=if value.chars().count()<=7{26.0}else if value.chars().count()<=10{22.0}else{19.0};label(ui,r.min+Vec2::new(18.0,66.0),value,size,true,p.text);}
   let mut y=50.0+summary_h+gap;
   if imported{label(ui,o+Vec2::new(6.0,y),"Перенесённое время учтено в общем счётчике. Календарь показывает сессии, для которых сохранились логи",13.0,false,p.muted);y+=import_h;}
   if paired{let ww=(width-gap)*0.375;self.week_chart(ui,region(o,0.0,y,ww,216.0),stats,today);self.heatmap(ui,region(o,ww+gap,y,width-ww-gap,242.0),stats,today);}else{self.week_chart(ui,region(o,0.0,y,width,216.0),stats,today);self.heatmap(ui,region(o,0.0,y+232.0,width,242.0),stats,today);}
   y+=charts_h+gap;
   let left_w=if narrow{width}else{(width-gap)/2.25};let right_w=width-left_w-gap;
   if show_versions{self.ranking(ui,region(o,0.0,y,left_w,version_h),"Любимые версии","layers",stats.map(|s|s.versions.iter().map(|(name,loader,time)|(name.clone(),*time,loader_color(loader))).collect()).unwrap_or_default(),"Пока пусто");}
   self.ranking(ui,region(o,0.0,y+if show_versions{version_h+gap}else{0.0},left_w,server_h),"Любимые серверы","server",stats.map(|s|s.servers.iter().map(|(name,time)|(name.clone(),*time,Color32::from_rgb(85,213,142))).collect()).unwrap_or_default(),"Пока не заходил на серверы");
   let recent=if narrow{region(o,0.0,y+left_h+gap,width,recent_h)}else{region(o,left_w+gap,y,right_w,recent_h.max(left_h))};
   ui.painter().rect_filled(recent,10.0,p.panel);self.stat_icon(ui,"history",Rect::from_min_size(recent.min+Vec2::splat(22.0),Vec2::splat(26.0)),p.accent);label(ui,recent.min+Vec2::new(58.0,29.0),"ПОСЛЕДНИЕ СЕССИИ",11.0,true,p.muted);
   if let Some(stats)=stats{for(i,session)in stats.recent.iter().take(recent_rows).enumerate(){let y=recent.top()+64.0+i as f32*38.0;let date=chrono::Local.timestamp_millis_opt(session.start).single();let when=date.map(|d|{let day=d.date_naive();let prefix=if day==today{"Сегодня".to_owned()}else if day==today-chrono::Duration::days(1){"Вчера".to_owned()}else{format!("{} {}",day.day(),SHORT[day.month0()as usize])};format!("{prefix}, {}",d.format("%H:%M"))}).unwrap_or_default();label(ui,egui::pos2(recent.left()+22.0,y),&when,14.0,true,p.text);let name=session.build_name.as_deref().or(session.pack.as_deref()).unwrap_or(&session.version_id);let time=duration(session.end.saturating_sub(session.start));ui.painter().text(egui::pos2(recent.right()-22.0,y),Align2::RIGHT_TOP,translations::text(&time),FontId::new(14.0,FontFamily::Name("aw-semibold".into())),p.text);let tag=region(egui::pos2(recent.right()-220.0,y-2.0),0.0,0.0,110.0,24.0);ui.painter().rect_filled(tag,4.0,mix(p.panel,loader_color(&session.loader),0.12));let short:String=name.chars().take(17).collect();raw_label(ui,tag.min+Vec2::new(7.0,4.0),&short,11.0,false,loader_color(&session.loader));}}
  });
    }
    fn ranking(
        &self,
        ui: &mut egui::Ui,
        r: Rect,
        title: &str,
        icon: &str,
        items: Vec<(String, i64, Color32)>,
        empty: &str,
    ) {
        let p = self.palette;
        ui.painter().rect_filled(r, 10.0, p.panel);
        self.stat_icon(
            ui,
            icon,
            Rect::from_min_size(r.min + Vec2::splat(22.0), Vec2::splat(26.0)),
            if icon == "server" {
                Color32::from_rgb(85, 213, 142)
            } else {
                Color32::from_rgb(148, 189, 213)
            },
        );
        label(
            ui,
            r.min + Vec2::new(58.0, 29.0),
            &title.to_uppercase(),
            11.0,
            true,
            p.muted,
        );
        let max = items.iter().map(|i| i.1).max().unwrap_or(1).max(1) as f32;
        if items.is_empty() {
            label(
                ui,
                r.min + Vec2::new(22.0, 62.0),
                empty,
                13.0,
                false,
                p.muted,
            );
        }
        for (i, (name, time, color)) in items.iter().enumerate() {
            let y = r.top() + 62.0 + i as f32 * 42.0;
            let time_width = ui
                .painter()
                .layout_no_wrap(duration(*time), FontId::proportional(13.0), p.muted)
                .size()
                .x;
            let mut clipped = name.clone();
            let max_width = (r.width() - 58.0 - time_width).max(12.0);
            while ui
                .painter()
                .layout_no_wrap(
                    clipped.clone(),
                    FontId::new(14.0, FontFamily::Name("aw-semibold".into())),
                    p.text,
                )
                .size()
                .x
                > max_width
                && !clipped.is_empty()
            {
                clipped.pop();
            }
            if clipped != *name {
                clipped.push('…');
            }
            raw_label(
                ui,
                egui::pos2(r.left() + 22.0, y),
                &clipped,
                14.0,
                true,
                p.text,
            );
            ui.painter().text(
                egui::pos2(r.right() - 22.0, y),
                Align2::RIGHT_TOP,
                translations::text(duration(*time)),
                FontId::proportional(13.0),
                p.muted,
            );
            let track = Rect::from_min_size(
                egui::pos2(r.left() + 22.0, y + 24.0),
                Vec2::new(r.width() - 44.0, 8.0),
            );
            ui.painter().rect_filled(track, 4.0, p.high);
            ui.painter().rect_filled(
                Rect::from_min_size(
                    track.min,
                    Vec2::new(track.width() * (*time as f32 / max), 8.0),
                ),
                4.0,
                *color,
            );
        }
    }
    fn week_chart(
        &mut self,
        ui: &mut egui::Ui,
        r: Rect,
        stats: Option<&activity::Stats>,
        today: chrono::NaiveDate,
    ) {
        let p = self.palette;
        ui.painter().rect_filled(r, 10.0, p.panel);
        let inner = r.shrink(22.0);
        label(ui, inner.min, "Время по дням", 15.0, true, p.text);
        let monday = today - chrono::Duration::days(today.weekday().num_days_from_monday() as i64)
            + chrono::Duration::weeks(self.activity_week_offset);
        let subtitle = if self.activity_week_offset == 0 {
            "Эта неделя".into()
        } else {
            let end = monday + chrono::Duration::days(6);
            format!(
                "{} {} — {} {} {}",
                monday.day(),
                SHORT[monday.month0() as usize],
                end.day(),
                SHORT[end.month0() as usize],
                end.year()
            )
        };
        label(
            ui,
            inner.min + Vec2::new(0.0, 21.0),
            &subtitle,
            13.0,
            false,
            p.muted,
        );
        let values: Vec<_> = (0..7)
            .map(|i| {
                let day = monday + chrono::Duration::days(i);
                if day > today {
                    0
                } else {
                    stats
                        .and_then(|s| s.days.get(&day))
                        .copied()
                        .unwrap_or(0)
                        .max(0)
                }
            })
            .collect();
        let total = values.iter().sum::<i64>();
        ui.painter().text(
            inner.right_top(),
            Align2::RIGHT_TOP,
            translations::text(stats.map(|_| duration(total)).unwrap_or_else(|| "…".into())),
            FontId::new(18.0, FontFamily::Name("aw-semibold".into())),
            p.text,
        );
        let text_w = ui
            .painter()
            .layout_no_wrap(
                translations::text(duration(total)),
                FontId::proportional(18.0),
                p.text,
            )
            .size()
            .x;
        for (left, x) in [
            (true, inner.right() - text_w - 66.0),
            (false, inner.right() - text_w - 34.0),
        ] {
            let hit = Rect::from_min_size(egui::pos2(x, r.top() + 22.0), Vec2::splat(32.0));
            if ui
                .interact(
                    hit,
                    ui.id().with(if left { "week-back" } else { "week-next" }),
                    Sense::click(),
                )
                .clicked()
            {
                if left {
                    self.activity_week_offset -= 1;
                } else if self.activity_week_offset < 0 {
                    self.activity_week_offset += 1;
                }
            }
            ui.painter().text(
                hit.center(),
                Align2::CENTER_CENTER,
                if left { "‹" } else { "›" },
                FontId::proportional(26.0),
                if left || self.activity_week_offset < 0 {
                    p.muted
                } else {
                    p.outline
                },
            );
        }
        let chart = Rect::from_min_size(
            inner.min + Vec2::new(0.0, 46.0),
            Vec2::new(inner.width(), 100.0),
        );
        for n in 0..4 {
            let y = chart.top() + chart.height() * n as f32 / 3.0;
            ui.painter().line_segment(
                [egui::pos2(chart.left(), y), egui::pos2(chart.right(), y)],
                Stroke::new(1.0_f32, p.outline.gamma_multiply(0.45)),
            );
        }
        let maximum = values.iter().copied().max().unwrap_or(1).max(1) as f32;
        let step = chart.width() / 7.0;
        for (i, time) in values.iter().enumerate() {
            let w = (step * 0.38).min(32.0);
            let h = if *time == 0 {
                3.0
            } else {
                (chart.height() * (*time as f32 / maximum)).max(3.0)
            };
            let bar = Rect::from_min_size(
                egui::pos2(
                    chart.left() + step * (i as f32 + 0.5) - w / 2.0,
                    chart.bottom() - h,
                ),
                Vec2::new(w, h),
            );
            ui.painter().rect_filled(
                bar,
                4.0,
                if *time > 0 {
                    mix(p.panel, p.accent, 0.65)
                } else {
                    p.high
                },
            );
            ui.painter().text(
                egui::pos2(
                    chart.left() + step * (i as f32 + 0.5),
                    chart.bottom() + 12.0,
                ),
                Align2::CENTER_TOP,
                translations::text(["пн", "вт", "ср", "чт", "пт", "сб", "вс"][i]),
                FontId::new(11.0, FontFamily::Name("aw-semibold".into())),
                p.muted,
            );
            let hover = Rect::from_min_max(
                egui::pos2(chart.left() + step * i as f32, chart.top()),
                egui::pos2(chart.left() + step * (i + 1) as f32, chart.bottom()),
            );
            let day = monday + chrono::Duration::days(i as i64);
            if day <= today {
                ui.interact(hover, ui.id().with(("week-day", i)), Sense::hover())
                    .on_hover_text(format!(
                        "{} {} · {}",
                        day.day(),
                        SHORT[day.month0() as usize],
                        if *time > 0 {
                            duration(*time)
                        } else {
                            "без игры".into()
                        }
                    ));
            }
        }
    }
    fn heatmap(
        &self,
        ui: &mut egui::Ui,
        r: Rect,
        stats: Option<&activity::Stats>,
        today: chrono::NaiveDate,
    ) {
        let p = self.palette;
        ui.painter().rect_filled(r, 10.0, p.panel);
        let inner = r.shrink(22.0);
        label(ui, inner.min, "Календарь активности", 15.0, true, p.text);
        let cell = if inner.width() < 520.0 { 10.0 } else { 13.0 };
        let gap = if cell == 13.0 { 4.0 } else { 3.0 };
        let stride = cell + gap;
        let weeks = (((inner.width() - 26.0) / stride) as usize).clamp(8, 53);
        let first = today
            - chrono::Duration::days(today.weekday().num_days_from_monday() as i64)
            - chrono::Duration::weeks((weeks - 1) as i64);
        let grid = inner.min + Vec2::new(26.0, 54.0);
        for (week, index) in (0..weeks).enumerate() {
            let start = first + chrono::Duration::days(index as i64 * 7);
            let first_label =
                week == 0 && (start + chrono::Duration::days(14)).month() == start.month();
            if first_label
                || (week > 0 && start.month() != (start - chrono::Duration::days(7)).month())
            {
                label(
                    ui,
                    grid + Vec2::new(week as f32 * stride, -20.0),
                    SHORT[start.month0() as usize],
                    11.0,
                    false,
                    p.muted,
                );
            }
            for day_index in 0..7 {
                let day = start + chrono::Duration::days(day_index);
                if day > today {
                    continue;
                }
                let value = stats.and_then(|s| s.days.get(&day)).copied().unwrap_or(0);
                let level = level(value);
                let color = if level == 0 {
                    p.high
                } else {
                    mix(p.panel, p.accent, [0.0, 0.25, 0.48, 0.74, 1.0][level])
                };
                let rect = Rect::from_min_size(
                    grid + Vec2::new(week as f32 * stride, day_index as f32 * stride),
                    Vec2::splat(cell),
                );
                ui.painter().rect_filled(rect, 3.0, color);
                if day == today {
                    ui.painter().rect_stroke(
                        rect,
                        3.0,
                        Stroke::new(1.5_f32, p.accent),
                        egui::StrokeKind::Inside,
                    );
                }
                ui.interact(
                    rect,
                    ui.id().with(("heat", week, day_index)),
                    Sense::hover(),
                )
                .on_hover_text(format!(
                    "{day} · {}",
                    if value > 0 {
                        duration(value)
                    } else {
                        "без игры".into()
                    }
                ));
            }
        }
        for (index, name) in [(0, "пн"), (2, "ср"), (4, "пт")] {
            label(
                ui,
                grid + Vec2::new(-26.0, index as f32 * stride),
                name,
                10.0,
                false,
                p.muted,
            );
        }
        let legend = inner.right_bottom() - Vec2::new(178.0, 14.0);
        label(ui, legend, "Меньше", 12.0, false, p.muted);
        for i in 0..5 {
            ui.painter().rect_filled(
                Rect::from_min_size(
                    legend + Vec2::new(62.0 + i as f32 * 17.0, 0.0),
                    Vec2::splat(13.0),
                ),
                3.0,
                if i == 0 {
                    p.high
                } else {
                    mix(p.panel, p.accent, [0.0, 0.25, 0.48, 0.74, 1.0][i])
                },
            );
        }
        label(
            ui,
            legend + Vec2::new(150.0, 0.0),
            "Больше",
            12.0,
            false,
            p.muted,
        );
    }
}
fn level(ms: i64) -> usize {
    if ms <= 0 {
        0
    } else if ms < 30 * 60_000 {
        1
    } else if ms < 60 * 60_000 {
        2
    } else if ms < 120 * 60_000 {
        3
    } else {
        4
    }
}
fn loader_color(loader: &str) -> Color32 {
    match loader {
        "FABRIC" => Color32::from_rgb(148, 189, 213),
        "FORGE" => Color32::from_rgb(238, 165, 110),
        "NEOFORGE" => Color32::from_rgb(241, 138, 138),
        "QUILT" => Color32::from_rgb(230, 192, 123),
        _ => Color32::from_rgb(183, 191, 204),
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn buckets_and_format_match_original() {
        assert_eq!(
            [0, 1, 29 * 60_000, 30 * 60_000, 60 * 60_000, 120 * 60_000].map(level),
            [0, 1, 1, 2, 3, 4]
        );
        assert_eq!(duration(3_600_000), "1 ч");
        assert_eq!(days(21), "21 день");
        assert_eq!(days(12), "12 дней");
    }
}
