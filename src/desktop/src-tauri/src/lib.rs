use tauri::{
    image::Image,
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager, State, WindowEvent, Wry,
};

const MAIN_WINDOW_LABEL: &str = "main";
const MENU_WORKSPACE_ID: &str = "workspace";
const MENU_QUICK_TIMELINE_ID: &str = "quick-timeline";
const MENU_FATIGUE_SURVEY_ID: &str = "fatigue-survey";
const MENU_QUIT_ID: &str = "quit";

struct TrayMenuState {
    fatigue_survey: MenuItem<Wry>,
}

fn show_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(MAIN_WINDOW_LABEL) {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn navigate_from_tray(app: &AppHandle, target: &str) {
    show_main_window(app);
    let _ = app.emit("desktop:navigate", target);
}

#[tauri::command]
fn set_pending_survey_menu(pending: bool, state: State<'_, TrayMenuState>) -> Result<(), String> {
    state
        .fatigue_survey
        .set_enabled(pending)
        .map_err(|error| error.to_string())
}

fn create_tray_icon() -> Image<'static> {
    const SIZE: u32 = 32;
    let mut rgba = vec![0_u8; (SIZE * SIZE * 4) as usize];

    for y in 0..SIZE {
        for x in 0..SIZE {
            let dx = x as i32 - 15;
            let dy = y as i32 - 15;
            let inside_circle = dx * dx + dy * dy <= 14 * 14;
            let clock_hand = (x == 15 && (8..=16).contains(&y))
                || (y == 15 && (15..=22).contains(&x));
            let offset = ((y * SIZE + x) * 4) as usize;

            if inside_circle {
                let color = if clock_hand {
                    [255, 255, 255, 255]
                } else {
                    [47, 128, 237, 255]
                };
                rgba[offset..offset + 4].copy_from_slice(&color);
            }
        }
    }

    Image::new_owned(rgba, SIZE, SIZE)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_http::init())
        .plugin(tauri_plugin_notification::init())
        .invoke_handler(tauri::generate_handler![set_pending_survey_menu])
        .setup(|app| {
            let workspace = MenuItem::with_id(app, MENU_WORKSPACE_ID, "打开工作台", true, None::<&str>)?;
            let quick_timeline = MenuItem::with_id(app, MENU_QUICK_TIMELINE_ID, "快捷时间轴", true, None::<&str>)?;
            let fatigue_survey = MenuItem::with_id(app, MENU_FATIGUE_SURVEY_ID, "填写今日日终调查", false, None::<&str>)?;
            let quit = MenuItem::with_id(app, MENU_QUIT_ID, "退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&workspace, &quick_timeline, &fatigue_survey, &quit])?;
            app.manage(TrayMenuState {
                fatigue_survey: fatigue_survey.clone(),
            });

            TrayIconBuilder::with_id("main-tray")
                .icon(create_tray_icon())
                .tooltip("Dayliane")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    MENU_WORKSPACE_ID => navigate_from_tray(app, "workspace"),
                    MENU_QUICK_TIMELINE_ID => navigate_from_tray(app, "quick-timeline"),
                    MENU_FATIGUE_SURVEY_ID => navigate_from_tray(app, "fatigue-survey"),
                    MENU_QUIT_ID => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        show_main_window(tray.app_handle());
                    }
                })
                .build(app)?;

            Ok(())
        })
        .on_window_event(|window, event| {
            if window.label() == MAIN_WINDOW_LABEL {
                if let WindowEvent::CloseRequested { api, .. } = event {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("failed to run Dayliane desktop application");
}
