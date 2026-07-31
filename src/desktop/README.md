# Dayliane Desktop

Dayliane desktop client built with Tauri 2.0, Vue 3, TypeScript, and Vite.

The desktop app reuses the user-facing pages, Pinia store, API client, components, utilities, and visual theme from `../web-user`. Desktop-only code lives in this directory and provides the native title bar, tray behavior, native notifications, always-on-top mode, and interface transparency.

## Development

Prerequisites:

- Node.js 22 or newer
- Rust stable toolchain with Cargo
- Windows: Microsoft C++ Build Tools and WebView2

```powershell
npm install
npm run dev
```

The browser preview runs at `http://127.0.0.1:1420`.

To run the native application after installing Rust:

```powershell
npm run tauri:dev
```

Build installers with:

```powershell
npm run tauri:build
```

The local backend defaults to `http://127.0.0.1:8080/api/v1`. Override it with `VITE_API_BASE_URL` when needed.
