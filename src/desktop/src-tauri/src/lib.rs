use tauri::{
    image::Image,
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Manager, WindowEvent,
};

const MAIN_WINDOW_LABEL: &str = "main";
const MENU_OPEN_ID: &str = "open";
const MENU_QUIT_ID: &str = "quit";

fn show_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(MAIN_WINDOW_LABEL) {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
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
        .setup(|app| {
            let open = MenuItem::with_id(app, MENU_OPEN_ID, "打开", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, MENU_QUIT_ID, "退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &quit])?;

            TrayIconBuilder::with_id("main-tray")
                .icon(create_tray_icon())
                .tooltip("Dayliane")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    MENU_OPEN_ID => show_main_window(app),
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
