// Serial Scope Lite launcher: opens index.html (next to this .exe) as an app window in
// Microsoft Edge, which ships with Windows 10/11 and supports Web Serial. Falls back to
// Chrome, then to the default browser.
#include <windows.h>
#include <shellapi.h>
#include <shlwapi.h>
#include <wchar.h>

static BOOL run(const wchar_t *exe, const wchar_t *args) {
    return (INT_PTR)ShellExecuteW(NULL, L"open", exe, args, NULL, SW_SHOWNORMAL) > 32;
}

int WINAPI wWinMain(HINSTANCE h, HINSTANCE p, PWSTR cmd, int show) {
    (void)h; (void)p; (void)cmd; (void)show;
    wchar_t html[MAX_PATH], url[3 * MAX_PATH], args[4 * MAX_PATH];
    DWORD n = ARRAYSIZE(url);

    GetModuleFileNameW(NULL, html, MAX_PATH);
    PathRemoveFileSpecW(html);
    PathAppendW(html, L"index.html");
    if (!PathFileExistsW(html)) {
        MessageBoxW(NULL, L"index.html was not found next to Serial Scope.exe.\n\n"
                          L"Unzip the whole folder first, then run Serial Scope.exe from it.",
                    L"Serial Scope", MB_ICONERROR);
        return 1;
    }
    if (FAILED(UrlCreateFromPathW(html, url, &n, 0))) return 1;
    swprintf(args, ARRAYSIZE(args), L"--app=\"%ls\" --window-size=1360,900", url);

    if (run(L"msedge.exe", args) || run(L"chrome.exe", args)) return 0;
    if ((INT_PTR)ShellExecuteW(NULL, L"open", html, NULL, NULL, SW_SHOWNORMAL) > 32) return 0;
    MessageBoxW(NULL, L"Couldn't start Microsoft Edge or Google Chrome.\n\n"
                      L"Install one of them, or open index.html in Chrome/Edge yourself.",
                L"Serial Scope", MB_ICONERROR);
    return 1;
}
