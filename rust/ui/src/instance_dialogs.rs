use super::*;
impl App {
    pub(super) fn open_instance_options(&mut self, build: &Build) {
        let options = library::build_dir(
            &self.game_root,
            &build.id,
            build.directory_name.as_deref(),
            build.pack,
        )
        .and_then(|dir| std::fs::read(dir.join("aw-instance.json")).ok())
        .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
        .unwrap_or(serde_json::json!({}));
        self.instance_name = build.name.clone();
        self.instance_memory = options["memoryMb"]
            .as_i64()
            .map(|v| v.to_string())
            .unwrap_or_default();
        self.instance_java = options["javaPath"].as_str().unwrap_or("").into();
        self.instance_jvm = options["jvmArgs"].as_str().unwrap_or("").into();
        self.instance_width = options["windowWidth"]
            .as_i64()
            .map(|v| v.to_string())
            .unwrap_or_default();
        self.instance_height = options["windowHeight"]
            .as_i64()
            .map(|v| v.to_string())
            .unwrap_or_default();
        self.instance_fullscreen = options["fullscreen"].as_bool();
        self.instance_favorite = options["favorite"].as_bool().unwrap_or(false);
        self.instance_world_history = options["historyIncludeWorlds"].as_bool().unwrap_or(false);
        self.instance_settings = Some(build.key());
    }
    pub(super) fn instance_dialogs(&mut self, ctx: &egui::Context) {
        if let Some(target) = self.import_type_target.clone() {
            egui::Window::new(translations::text("Что добавить в сборку?")).anchor(egui::Align2::CENTER_CENTER,Vec2::ZERO).collapsible(false).resizable(false).show(ctx,|ui|{
            for(label,kind)in[("Моды (.jar)","mod"),("Шейдеры (.zip)","shader"),("Ресурспаки (.zip)","resourcepack"),("Датапаки (.zip)","datapack")]{if ui.button(translations::text(label)).clicked(){self.import_type_target=None;if kind=="datapack"{self.import_world_target=Some(target.clone());self.import_world.clear();}else{self.downloads.enqueue("Добавление файлов".into(),serde_json::json!({"op":"importFiles","target":target,"kind":kind}));}}}
            if ui.button(translations::text("Отмена")).clicked(){self.import_type_target=None;}
        });
        }
        let create_events: Vec<_> = self
            .create_engine
            .as_ref()
            .map(|engine| engine.rx.try_iter().collect())
            .unwrap_or_default();
        for event in create_events {
            match event {
                tasks::Event::Data(event) if event["type"] == "done" => {
                    self.create_versions = event["data"].as_array().cloned().unwrap_or_default()
                }
                tasks::Event::Data(event) if event["type"] == "failed" => {
                    self.message = event["data"].as_str().map(str::to_owned)
                }
                tasks::Event::Exited(_) => self.create_engine = None,
                _ => {}
            }
        }
        if self.create_build_open {
            let mut open = true;
            egui::Window::new(translations::text("Новая сборка")).open(&mut open).anchor(egui::Align2::CENTER_CENTER,Vec2::ZERO).collapsible(false).default_width(520.0).show(ctx,|ui|{
            ui.label(translations::text("Название сборки"));ui.add(egui::TextEdit::singleline(&mut self.create_name).desired_width(ui.available_width()));
            ui.label("Minecraft");if self.create_engine.is_some(){ui.label(translations::text("Загружаю версии…"));}
            ui.add(egui::TextEdit::singleline(&mut self.create_search).hint_text(translations::text("Поиск версии")));
            let snapshots=self.preferences.boolean("showSnapshots",false);let old=self.preferences.boolean("showOldVersions",false);
            egui::ComboBox::from_id_salt("new-build-version").selected_text(if self.create_version.is_empty(){translations::text("Выбери версию")}else{self.create_version.clone()}).width(350.0).show_ui(ui,|ui|{
                for version in self.create_versions.iter().filter(|v|{let kind=v["type"].as_str().unwrap_or("");(kind=="release"||(snapshots&&kind=="snapshot")||(old&&matches!(kind,"old_alpha"|"old_beta")))&&v["id"].as_str().unwrap_or("").contains(self.create_search.trim())}){
                    if let Some(id)=version["id"].as_str(){ui.selectable_value(&mut self.create_version,id.to_owned(),id);}
                }
            });
            ui.label(translations::text("Загрузчик"));egui::ComboBox::from_id_salt("new-build-loader").selected_text(&self.create_loader).show_ui(ui,|ui|{for loader in["VANILLA","FABRIC","FORGE","NEOFORGE","QUILT"]{ui.selectable_value(&mut self.create_loader,loader.to_owned(),loader);}});
            let valid=!self.create_name.trim().is_empty()&&self.create_versions.iter().any(|v|v["id"]==self.create_version);
            if ui.add_enabled(valid,egui::Button::new(RichText::new(translations::text("Создать сборку")).color(self.palette.on_accent)).fill(self.palette.accent)).clicked(){self.downloads.enqueue(format!("Создание {}",self.create_name),serde_json::json!({"op":"createBuild","title":self.create_name,"version":self.create_version,"kind":self.create_loader}));self.create_build_open=false;}
        });
            if !open {
                self.create_build_open = false;
            }
        }
        if let Some(key) = self.import_world_target.clone() {
            let worlds: Vec<String> = self
                .builds
                .iter()
                .find(|build| build.key() == key)
                .and_then(|build| {
                    library::build_dir(
                        &self.game_root,
                        &build.id,
                        build.directory_name.as_deref(),
                        build.pack,
                    )
                })
                .map(|root| {
                    std::fs::read_dir(root.join("saves"))
                        .into_iter()
                        .flatten()
                        .flatten()
                        .filter(|e| {
                            e.file_type()
                                .map(|t| t.is_dir() && !t.is_symlink())
                                .unwrap_or(false)
                                && e.path().join("level.dat").is_file()
                        })
                        .map(|e| e.file_name().to_string_lossy().into_owned())
                        .collect()
                })
                .unwrap_or_default();
            egui::Window::new(translations::text("Выбери мир для датапака")).anchor(egui::Align2::CENTER_CENTER,Vec2::ZERO).collapsible(false).resizable(false).show(ctx,|ui|{
                if worlds.is_empty(){ui.label(translations::text("Миров пока нет. Создай мир в Minecraft."));}
                for world in &worlds{ui.selectable_value(&mut self.import_world,world.clone(),world);}
                ui.horizontal(|ui|{if ui.button(translations::text("Отмена")).clicked(){self.import_world_target=None;}if ui.add_enabled(worlds.contains(&self.import_world),egui::Button::new(translations::text("Выбрать файлы"))).clicked(){self.downloads.enqueue("Добавление датапаков".into(),serde_json::json!({"op":"importFiles","target":key,"kind":"datapack","world":self.import_world}));self.import_world_target=None;}});
            });
        }
        if let Some(key) = self.instance_settings.clone() {
            if let Some(build) = self.builds.iter().find(|b| b.key() == key).cloned() {
                let mut open = true;
                egui::Window::new(translations::text("Настройки сборки")).open(&mut open).anchor(egui::Align2::CENTER_CENTER,Vec2::ZERO).default_size([640.0,520.0]).collapsible(false).show(ctx,|ui|{
                egui::ScrollArea::vertical().show(ui,|ui|{
                    ui.heading(&build.name);ui.label(translations::text("Пустые поля используют общие настройки лаунчера."));ui.separator();
                    if !build.pack{ui.label(translations::text("Название сборки"));ui.add(egui::TextEdit::singleline(&mut self.instance_name).desired_width(ui.available_width()));if ui.button(translations::text("Переименовать")).clicked(){self.downloads.enqueue("Переименование сборки".into(),serde_json::json!({"op":"rename","target":key,"title":self.instance_name}));}}
                    ui.checkbox(&mut self.instance_favorite,translations::text("Избранное"));
                    ui.checkbox(&mut self.instance_world_history,translations::text("Сохранять миры в новых снимках истории"));
                    ui.separator();ui.label(RichText::new(translations::text("Память и Java")).family(FontFamily::Name("aw-semibold".into())));
                    ui.label(translations::text("Память, МБ"));ui.add(egui::TextEdit::singleline(&mut self.instance_memory).hint_text(translations::text("Как в общих настройках")).desired_width(ui.available_width()));
                    ui.label(translations::text("Исполняемый файл Java"));ui.add(egui::TextEdit::singleline(&mut self.instance_java).hint_text(translations::text("Автоматически")).desired_width(ui.available_width()));
                    ui.label(translations::text("Аргументы JVM"));ui.add(egui::TextEdit::multiline(&mut self.instance_jvm).desired_width(ui.available_width()).desired_rows(3));
                    ui.separator();ui.label(RichText::new(translations::text("Окно игры")).family(FontFamily::Name("aw-semibold".into())));
                    ui.horizontal(|ui|{ui.label(translations::text("Ширина"));ui.add(egui::TextEdit::singleline(&mut self.instance_width).desired_width(110.0));ui.label(translations::text("Высота"));ui.add(egui::TextEdit::singleline(&mut self.instance_height).desired_width(110.0));});
                    egui::ComboBox::from_id_salt("instance-fullscreen").selected_text(translations::text(match self.instance_fullscreen{None=>"Как в настройках игры",Some(true)=>"Полноэкранный",Some(false)=>"Оконный"})).show_ui(ui,|ui|{ui.selectable_value(&mut self.instance_fullscreen,None,translations::text("Как в настройках игры"));ui.selectable_value(&mut self.instance_fullscreen,Some(true),translations::text("Полноэкранный"));ui.selectable_value(&mut self.instance_fullscreen,Some(false),translations::text("Оконный"));});
                    ui.add_space(12.0);if ui.button(translations::text("Сохранить")).clicked(){
                        let valid=[self.instance_memory.trim(),self.instance_width.trim(),self.instance_height.trim()].iter().all(|v|v.is_empty()||v.parse::<i64>().is_ok());
                        if valid{let parse=|s:&str|s.trim().parse::<i64>().ok();self.downloads.enqueue(format!("Настройки {}",build.name),serde_json::json!({"op":"saveOptions","target":key,"options":{"memoryMb":parse(&self.instance_memory),"javaPath":self.instance_java,"jvmArgs":self.instance_jvm,"windowWidth":parse(&self.instance_width),"windowHeight":parse(&self.instance_height),"fullscreen":self.instance_fullscreen,"favorite":self.instance_favorite,"historyIncludeWorlds":self.instance_world_history}}));self.instance_settings=None;}else{self.message=Some("В числовых полях нужны целые значения".into());}
                    }
                });
            });
                if !open {
                    self.instance_settings = None;
                }
            } else {
                self.instance_settings = None;
            }
        }
        if let Some(key) = self.instance_menu.clone() {
            if let Some(build) = self.builds.iter().find(|b| b.key() == key).cloned() {
                let mut open = true;
                egui::Window::new(translations::text("Действия со сборкой")).open(&mut open).anchor(egui::Align2::CENTER_CENTER,Vec2::ZERO).collapsible(false).resizable(false).show(ctx,|ui|{
            if ui.button(translations::text("Настройки")).clicked(){self.open_instance_options(&build);self.instance_menu=None;}
            if ui.button(translations::text("Открыть папку")).clicked(){if let Some(dir)=library::build_dir(&self.game_root,&build.id,build.directory_name.as_deref(),build.pack){if let Err(error)=files::show_folder(&dir,Path::new("")){self.message=Some(error.to_string());}}self.instance_menu=None;}
            for (label,op) in [("Создать ярлык","shortcut"),("Экспортировать в .mrpack","export"),("Создать копию","duplicate"),("Сохранить состояние","snapshot")] {
                if ui.button(translations::text(label)).clicked(){self.downloads.enqueue(format!("{label}: {}",build.name),serde_json::json!({"op":op,"target":key,"title":if op=="duplicate"{format!("{} — копия",build.name)}else{build.name.clone()}}));self.instance_menu=None;}
            }
        });
                if !open {
                    self.instance_menu = None;
                }
            } else {
                self.instance_menu = None;
            }
        }
    }
}
