use super::*;
use serde_json::Value;

impl App {
    fn setting_toggle(
        &mut self,
        ui: &mut egui::Ui,
        label: &str,
        hint: Option<&str>,
        key: &str,
        default: bool,
        enabled: bool,
    ) {
        let value = self.preferences.boolean(key, default);
        let label = translations::text(label);
        let hint = hint.map(translations::text);
        let p = self.palette;
        let height = 64.0;
        let (r, _) = ui.allocate_exact_size(
            Vec2::new(ui.available_width(), height),
            egui::Sense::hover(),
        );
        let color = if enabled { p.text } else { p.muted };
        let y = if hint.is_some() {
            r.top() + 15.0
        } else {
            r.center().y - 9.0
        };
        ui.painter().text(
            egui::pos2(r.left(), y),
            egui::Align2::LEFT_TOP,
            label,
            egui::FontId::proportional(14.0),
            color,
        );
        if let Some(hint) = hint {
            ui.painter().text(
                egui::pos2(r.left(), y + 23.0),
                egui::Align2::LEFT_TOP,
                hint,
                egui::FontId::proportional(13.0),
                p.muted,
            );
        }
        let switch = egui::Rect::from_center_size(
            egui::pos2(r.right() - 26.0, r.center().y),
            Vec2::new(52.0, 32.0),
        );
        ui.painter()
            .rect_filled(switch, 16.0, if value { p.accent } else { p.high });
        if !value {
            ui.painter().rect_stroke(
                switch,
                16.0,
                egui::Stroke::new(2.0_f32, p.muted.gamma_multiply(0.65)),
                egui::StrokeKind::Inside,
            );
        }
        ui.painter().circle_filled(
            egui::pos2(
                if value {
                    switch.right() - 16.0
                } else {
                    switch.left() + 16.0
                },
                switch.center().y,
            ),
            if value { 12.0 } else { 8.0 },
            if value { p.on_accent } else { p.muted },
        );
        if enabled
            && ui
                .interact(switch, ui.id().with(key), egui::Sense::click())
                .clicked()
        {
            self.preferences.set(key, Value::Bool(!value));
        }
    }
    pub(super) fn settings_body(&mut self, ui: &mut egui::Ui) {
        let p = self.palette;
        ui.spacing_mut().slider_width = (ui.available_width() - 36.0).max(100.0);
        ui.spacing_mut().interact_size.y = 44.0;
        ui.spacing_mut().button_padding = Vec2::new(14.0, 10.0);
        ui.visuals_mut().extreme_bg_color = p.high;
        ui.spacing_mut().item_spacing.y = 0.0;
        ui.visuals_mut().widgets.inactive.weak_bg_fill = p.high;
        let group = || {
            egui::Frame::NONE
                .fill(p.high)
                .corner_radius(6.0)
                .stroke(egui::Stroke::new(1.0_f32, p.outline))
                .inner_margin(16.0)
        };
        if let Some(error) = &self.preferences.error {
            ui.colored_label(egui::Color32::LIGHT_RED, translations::text(error));
        }
        if let Some(notice) = &self.settings_notice {
            ui.colored_label(p.accent, translations::text(notice));
        }
        egui::ScrollArea::vertical().id_salt(("settings-content",self.settings_section)).auto_shrink([false,false]).show(ui,|ui|{
            ui.set_min_width(ui.available_width());
            match self.settings_section {
                0=>self.exact_accounts(ui),
                1=>{group().show(ui,|ui|{
                    ui.set_min_width((ui.available_width()-32.0).max(0.0));
                    self.settings_heading(ui,"Поведение лаунчера");
                    self.setting_toggle(ui,"Не закрывать лаунчер при запуске игры",None,"keepLauncherOpen",false,true);
                    self.setting_toggle(ui,"Игра с друзьями",None,"friendsEnabled",false,false);
                });},
                2=>{
                    let total=self.preferences.total_mb;let limit=(total-1024).max(512);let recommended=settings::recommended(total);
                    group().show(ui,|ui|{
                        ui.set_min_width(ui.available_width());
                        self.settings_heading(ui,"Память");
                        self.settings_line(ui,&format!("{} для игры · в компьютере {}", Self::memory_label(self.preferences.number("memoryMb",recommended)),format!("{} ГБ",(total+512)/1024)),14.0,p.text,20.0);
                        ui.add_space(12.0);
                        self.settings_line(ui,&format!("Рекомендуется для этого ПК: {}",Self::memory_label(recommended)),13.0,p.accent,18.0);
                        ui.add_space(8.0);
                        let mut memory=self.memory_edit.parse::<i64>().unwrap_or(recommended).clamp(512,limit);
                        let (rect,response)=ui.allocate_exact_size(Vec2::new(ui.available_width(),48.0),egui::Sense::click_and_drag());
                        let start=rect.left_center()+Vec2::new(10.0,0.0);let end=rect.right_center()-Vec2::new(10.0,0.0);
                        let fraction=(memory-512)as f32/(limit-512).max(1)as f32;
                        ui.painter().line_segment([start,end],egui::Stroke::new(4.0_f32,p.muted.gamma_multiply(0.3)));
                        let thumb=start+Vec2::new((end.x-start.x)*fraction,0.0);
                        ui.painter().line_segment([start,thumb],egui::Stroke::new(4.0_f32,p.accent));ui.painter().circle_filled(thumb,10.0,p.accent);
                        if response.dragged()||response.clicked(){if let Some(pos)=response.interact_pointer_pos(){let raw=512.0+((pos.x-start.x)/(end.x-start.x)).clamp(0.0,1.0)*(limit-512)as f32;memory=((raw/256.0).floor()as i64*256).clamp(512,limit);self.memory_edit=memory.to_string();}}
                        ui.add_space(14.0);
                        let valid=self.memory_edit.parse::<i64>().ok().filter(|v|(512..=limit).contains(v));
                        let (field,_)=ui.allocate_exact_size(Vec2::new(ui.available_width(),56.0),egui::Sense::hover());
                        ui.painter().rect_stroke(field,6.0,egui::Stroke::new(1.0_f32,if valid.is_some(){p.muted.gamma_multiply(0.55)}else{egui::Color32::LIGHT_RED}),egui::StrokeKind::Inside);
                        let label=egui::pos2(field.left()+12.0,field.top()-7.0);ui.painter().rect_filled(egui::Rect::from_min_size(label,Vec2::new(116.0,16.0)),0.0,p.high);ui.painter().text(label,egui::Align2::LEFT_TOP,translations::text("Своя память, МБ"),egui::FontId::proportional(12.0),p.muted);
                        ui.put(field.shrink2(Vec2::new(12.0,12.0)),egui::TextEdit::singleline(&mut self.memory_edit).frame(false).margin(egui::Margin::ZERO).desired_width(field.width()-24.0));
                        ui.add_space(4.0);self.settings_line(ui,&format!("От 512 до {limit} МБ · оставляем память системе"),12.0,p.muted,20.0);
                        ui.add_space(10.0);
                        ui.horizontal_wrapped(|ui|{
                            if self.settings_button(ui,"Применить",valid.is_some()&&valid!=Some(self.preferences.number("memoryMb",recommended))){self.preferences.set("memoryMb",Value::from(valid.unwrap()));}
                            if self.settings_button(ui,"Использовать рекомендуемую",true)&&self.preferences.set("memoryMb",Value::from(recommended)){self.memory_edit=recommended.to_string();}
                        });
                    });ui.add_space(16.0);
                    group().show(ui,|ui|{ui.set_min_width((ui.available_width()-32.0).max(0.0));self.settings_heading(ui,"Список версий");self.setting_toggle(ui,"Показывать снапшоты",None,"showSnapshots",false,true);self.setting_toggle(ui,"Показывать alpha и beta",None,"showOldVersions",false,true);});ui.add_space(16.0);
                    group().show(ui,|ui|{ui.set_min_width(ui.available_width());self.settings_heading(ui,"Аргументы JVM");
                        let changed=ui.add_sized([ui.available_width(),96.0],egui::TextEdit::multiline(&mut self.jvm_edit).margin(Vec2::new(12.0,12.0)).font(egui::FontId::proportional(13.0))).changed();
                        if changed{self.preferences.set("jvmArgs",Value::from(self.jvm_edit.clone()));}
                        ui.add_space(8.0);
                        if self.settings_button(ui,"Сбросить к рекомендуемым",true)&&self.preferences.set("jvmArgs",Value::from(settings::JVM_ARGS)){self.jvm_edit=settings::JVM_ARGS.into();}
                    });
                },
                3=>{group().show(ui,|ui|{ui.set_min_width((ui.available_width()-32.0).max(0.0));ui.horizontal_wrapped(|ui|{
                    for (mode,key,label) in [(ThemeMode::System,"SYSTEM","Как в системе"),(ThemeMode::Dark,"DARK","Тёмная"),(ThemeMode::Light,"LIGHT","Светлая"),(ThemeMode::Oled,"OLED","OLED · чёрная")]{
                        if ui.add(egui::Button::new(RichText::new(translations::text(label)).color(if self.theme_mode==mode{p.accent}else{p.text})).fill(if self.theme_mode==mode{p.selected}else{p.high})).clicked()&&self.preferences.set("themeMode",Value::from(key)){self.theme_mode=mode;}
                    }
                });});},
                4=>{
                    ui.label(RichText::new(translations::text("Язык приложения")).family(FontFamily::Name("aw-semibold".into())).size(15.0));ui.add(egui::TextEdit::singleline(&mut self.language_search).margin(Vec2::new(12.0,10.0)).hint_text(translations::text("Поиск языка…")).desired_width(ui.available_width()));ui.add_space(8.0);
                    let query=self.language_search.trim().to_lowercase();let selected=self.preferences.string("language","EN");
                    for language in settings::languages().iter().filter(|l|query.is_empty()||format!("{} {} {}",l.label,l.tag,l.id).to_lowercase().contains(&query)){
                        let active=selected==language.id;
                        let (rect,response)=ui.allocate_exact_size(Vec2::new(ui.available_width(),48.0),egui::Sense::click());
                        if active{ui.painter().rect_filled(rect,6.0,p.selected);}
                        let flag=if language.id=="RU"{"ru".to_owned()}else if language.id=="CA"{"es-ct".to_owned()}else{language.tag.rsplit('-').next().unwrap_or("").to_lowercase()};
                        if let Some(texture)=self.nav_icons.get(&format!("flag:{flag}")){ui.painter().image(texture.id(),egui::Rect::from_min_size(rect.min+Vec2::new(12.0,13.0),Vec2::new(28.0,21.0)),egui::Rect::from_min_max(egui::Pos2::ZERO,egui::pos2(1.0,1.0)),egui::Color32::WHITE);}
                        ui.painter().text(rect.min+Vec2::new(52.0,7.0),egui::Align2::LEFT_TOP,&language.label,egui::FontId::proportional(14.0),if active{p.accent}else{p.text});
                        ui.painter().text(rect.min+Vec2::new(52.0,28.0),egui::Align2::LEFT_TOP,&language.tag,egui::FontId::proportional(11.0),p.muted);
                        let center=rect.right_center()-Vec2::new(18.0,0.0);ui.painter().circle_stroke(center,8.0,egui::Stroke::new(1.5_f32,if active{p.accent}else{p.muted}));if active{ui.painter().circle_filled(center,4.0,p.accent);}
                        if response.clicked(){self.preferences.set("language",Value::from(language.id.clone()));}
                    }
                    ui.label(RichText::new(translations::text("Переводы и шрифты доступны без интернета")).color(p.muted).size(12.0));
                },
                5=>{group().show(ui,|ui|{
                    ui.set_min_width((ui.available_width()-32.0).max(0.0));ui.label(RichText::new(translations::text("Загрузка и целостность")).family(FontFamily::Name("aw-semibold".into())).size(15.0));
                    let auto=(std::thread::available_parallelism().map(|v|v.get()as i64).unwrap_or(2)*4).clamp(8,24);
                    let saved=self.preferences.number("downloadConcurrency",0);let mut threads=if saved==0{auto}else{saved};
                    ui.label(translations::text(format!("Потоков загрузки: {threads}")));
                    if ui.add(egui::Slider::new(&mut threads,4..=32)).changed(){self.preferences.set("downloadConcurrency",Value::from(threads));}
                    ui.horizontal_wrapped(|ui|{
                        if ui.button(translations::text("Сбросить кэш проверки")).clicked() && self.preferences.reset_verify_cache(){self.settings_notice=Some("Кэш проверки сброшен — следующий запуск заново пересчитает хеши всех файлов".into());}
                        if ui.button(translations::text("Открыть папку лаунчера")).clicked(){let root=self.preferences.path.parent().unwrap();if let Err(error)=files::show_folder(root,Path::new("")){self.preferences.error=Some(error.to_string());}}
                    });
                    ui.label(RichText::new(translations::text("Сброс кэша проверки помогает, когда игра вылетает из-за повреждённых файлов: лаунчер перепроверит и докачает всё, что не сходится.")).color(p.muted).size(12.0));
                });},
                6=>{
                    ui.label(RichText::new("Discord Rich Presence").family(FontFamily::Name("aw-semibold".into())).size(15.0));ui.label(RichText::new(translations::text("Покажи друзьям, во что играешь")).color(p.muted));
                    group().show(ui,|ui|{ui.set_min_width((ui.available_width()-32.0).max(0.0));ui.horizontal(|ui|{if let Some(logo)=&self.logo{ui.add(egui::Image::new(logo).fit_to_exact_size(Vec2::splat(64.0)));}ui.vertical(|ui|{ui.label(RichText::new("AWLauncher").family(FontFamily::Name("aw-semibold".into())));ui.label(translations::text("Лаунчер Minecraft Java Edition"));});});ui.separator();ui.label(RichText::new(translations::text("С таймером текущей сессии и кнопкой сайта")).color(p.muted));});ui.add_space(16.0);
                    group().show(ui,|ui|{ui.set_min_width((ui.available_width()-32.0).max(0.0));let enabled=self.preferences.boolean("discordPresence",true);self.setting_toggle(ui,"Показывать активность в Discord",Some("Сборка, версия Minecraft и время игры"),"discordPresence",true,true);self.setting_toggle(ui,"Показывать открытый лаунчер",Some("Активность появится только во время игры"),"discordShowLauncher",true,enabled);self.setting_toggle(ui,"Показывать название сборки",Some("Для сборок из каталога добавится кнопка их страницы"),"discordShowInstance",true,enabled);self.setting_toggle(ui,"Показывать адрес сервера",Some("Друзья увидят адрес сервера"),"discordShowServer",false,enabled);});
                    ui.label(RichText::new(translations::text("Параметры сохраняются для игрового движка. Discord IPC в Rust-версии ещё переносится.")).color(p.muted).size(12.0));
                },
                7=>{
                    ui.label(RichText::new(translations::text("О лаунчере")).family(FontFamily::Name("aw-semibold".into())).size(15.0));ui.label(RichText::new("AWLauncher").size(32.0).family(FontFamily::Name("aw-semibold".into())).color(p.accent));ui.label(translations::text("Версия лаунчера: 1.0.50"));ui.label(RichText::new(translations::text("Экспериментальный интерфейс на Rust")).color(p.muted));
                    ui.hyperlink_to(translations::text("Открыть GitHub"),"https://github.com/AlpheusWorld/AWLauncher-releases");
                    if ui.button(translations::text("Открыть папку лаунчера")).clicked(){if let Some(root)=self.preferences.path.parent(){if let Err(error)=files::show_folder(root,Path::new("")){self.preferences.error=Some(error.to_string());}}}
                    let active=self.downloads.items.iter().any(|item|item.status.pending());
                    if ui.add_enabled(!active,egui::Button::new(translations::text("Проверить обновления"))).clicked(){self.downloads.enqueue("Проверка обновлений".into(),serde_json::json!({"op":"checkUpdate"}));}
                    let checked=self.downloads.items.iter().rev().find(|item|item.request["op"]=="checkUpdate").map(|item|(item.result.clone(),item.error.clone()));
                    if let Some(item)=checked{if let Some(result)=&item.0{ui.label(translations::text(result["message"].as_str().unwrap_or("")));if result["available"].as_str().is_some()&&ui.add_enabled(!active&&self.running_games.is_empty(),egui::Button::new(translations::text("Установить обновление"))).clicked(){self.downloads.enqueue("Обновление лаунчера".into(),serde_json::json!({"op":"installUpdate","games":self.running_games.len()}));}}if let Some(error)=&item.1{ui.colored_label(egui::Color32::LIGHT_RED,translations::text(error));}}

                },_=>{}
            }
            if let Some(error)=&self.preferences.error{ui.colored_label(egui::Color32::LIGHT_RED,translations::text(error));}
            if let Some(notice)=&self.settings_notice{ui.colored_label(p.accent,translations::text(notice));}
        });
    }
}
impl App {
    fn settings_line(
        &self,
        ui: &mut egui::Ui,
        label: &str,
        size: f32,
        color: egui::Color32,
        height: f32,
    ) {
        let label = translations::text(label);
        let (rect, _) = ui.allocate_exact_size(
            Vec2::new(ui.available_width(), height),
            egui::Sense::hover(),
        );
        ui.painter().text(
            rect.min,
            egui::Align2::LEFT_TOP,
            label,
            egui::FontId::proportional(size),
            color,
        );
    }
    fn settings_heading(&self, ui: &mut egui::Ui, label: &str) {
        let label = translations::text(label);
        let (rect, _) =
            ui.allocate_exact_size(Vec2::new(ui.available_width(), 29.0), egui::Sense::hover());
        ui.painter().text(
            rect.min,
            egui::Align2::LEFT_TOP,
            label,
            egui::FontId::new(15.0, egui::FontFamily::Name("aw-semibold".into())),
            self.palette.text,
        );
    }
    fn settings_button(&self, ui: &mut egui::Ui, label: &str, enabled: bool) -> bool {
        let label = translations::text(label);
        let font = egui::FontId::new(14.0, egui::FontFamily::Name("aw-semibold".into()));
        let text = ui.painter().layout_no_wrap(
            label.into(),
            font,
            if enabled {
                self.palette.text
            } else {
                self.palette.muted.gamma_multiply(0.5)
            },
        );
        let (rect, response) = ui.allocate_exact_size(
            Vec2::new(text.size().x + 36.0, 44.0),
            if enabled {
                egui::Sense::click()
            } else {
                egui::Sense::hover()
            },
        );
        ui.painter().rect_filled(
            rect,
            6.0,
            if response.hovered() && enabled {
                self.palette.selected
            } else {
                self.palette.high
            },
        );
        ui.painter()
            .galley(rect.center() - text.size() / 2.0, text, self.palette.text);
        enabled && response.clicked()
    }
    fn memory_label(memory: i64) -> String {
        if memory < 1024 {
            return format!("{memory} МБ");
        }
        if memory % 1024 == 0 {
            format!("{} ГБ", memory / 1024)
        } else {
            format!("{:.1} ГБ", memory as f64 / 1024.0).replace('.', ",")
        }
    }
}
