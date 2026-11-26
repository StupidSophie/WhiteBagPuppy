/*
 * White Bag Puppy.exe: installs White Bag Puppy (with its own Java) into %LOCALAPPDATA%\WhiteBagPuppy, away from
 * OneDrive and protected folders, then starts it. Run it again to update; your settings and history are kept.
 *
 * The app itself (WhiteBagPuppy.jar, the Java runtime, the icon) is a zip appended to this exe, followed by a
 * trailer: 32 bytes of version text, the zip's offset (8 bytes, little-endian) and the magic "WBPPAYL1".
 */
#define WIN32_LEAN_AND_MEAN
#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <windows.h>
#include <shellapi.h>
#include <shlobj.h>
#include <objbase.h>
#include <shobjidl.h>
#include <wchar.h>
#include <stdio.h>

#define APP L"White Bag Puppy"
#define MAGIC "WBPPAYL1"

static wchar_t self[MAX_PATH], selfDir[MAX_PATH], dest[MAX_PATH];

static void fail(const wchar_t *what) {
    wchar_t msg[2048];
    swprintf(msg, 2048, L"%ls\n\nIf this keeps happening, send a screenshot of this to whoever gave you the puppy.", what);
    MessageBoxW(NULL, msg, APP, MB_OK | MB_ICONWARNING);
}

static BOOL exists(const wchar_t *p) { return GetFileAttributesW(p) != INVALID_FILE_ATTRIBUTES; }

static void join(wchar_t *out, const wchar_t *a, const wchar_t *b) { swprintf(out, MAX_PATH, L"%ls\\%ls", a, b); }

/* Runs a command line hidden and waits for it. Returns its exit code (or -1). */
static DWORD run(wchar_t *cmd, const wchar_t *dir) {
    STARTUPINFOW si; PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof si); si.cb = sizeof si; si.dwFlags = STARTF_USESHOWWINDOW; si.wShowWindow = SW_HIDE;
    if (!CreateProcessW(NULL, cmd, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, dir, &si, &pi)) return (DWORD)-1;
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = (DWORD)-1;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
    return code;
}

/* Copies or deletes files/folders with Explorer's own copier (folders included), quietly. */
static BOOL shellOp(UINT op, const wchar_t *from, const wchar_t *to) {
    wchar_t f[MAX_PATH + 2] = {0}, t[MAX_PATH + 2] = {0};
    wcsncpy(f, from, MAX_PATH);
    if (to) wcsncpy(t, to, MAX_PATH);
    SHFILEOPSTRUCTW s; ZeroMemory(&s, sizeof s);
    s.wFunc = op; s.pFrom = f; s.pTo = to ? t : NULL;
    s.fFlags = FOF_NO_UI | FOF_NOCONFIRMMKDIR;
    return SHFileOperationW(&s) == 0 && !s.fAnyOperationsAborted;
}

static BOOL readFileText(const wchar_t *p, char *buf, DWORD size) {
    HANDLE h = CreateFileW(p, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING, 0, NULL);
    if (h == INVALID_HANDLE_VALUE) return FALSE;
    DWORD n = 0; BOOL ok = ReadFile(h, buf, size - 1, &n, NULL); CloseHandle(h);
    buf[ok ? n : 0] = 0;
    return ok;
}

static void writeFileText(const wchar_t *p, const char *text) {
    HANDLE h = CreateFileW(p, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    if (h == INVALID_HANDLE_VALUE) return;
    DWORD n; WriteFile(h, text, (DWORD)strlen(text), &n, NULL); CloseHandle(h);
}

static ULONGLONG mtime(const wchar_t *p) {
    WIN32_FILE_ATTRIBUTE_DATA d;
    if (!GetFileAttributesExW(p, GetFileExInfoStandard, &d)) return 0;
    return ((ULONGLONG)d.ftLastWriteTime.dwHighDateTime << 32) | d.ftLastWriteTime.dwLowDateTime;
}

/* Reads the trailer: the version text and where the zip starts. */
static BOOL payload(char version[33], ULONGLONG *offset, ULONGLONG *end) {
    HANDLE h = CreateFileW(self, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (h == INVALID_HANDLE_VALUE) return FALSE;
    LARGE_INTEGER size; GetFileSizeEx(h, &size);
    BOOL ok = FALSE;
    if (size.QuadPart > 48) {
        LARGE_INTEGER pos; pos.QuadPart = size.QuadPart - 48;
        unsigned char t[48]; DWORD n = 0;
        if (SetFilePointerEx(h, pos, NULL, FILE_BEGIN) && ReadFile(h, t, 48, &n, NULL) && n == 48 && memcmp(t + 40, MAGIC, 8) == 0) {
            memcpy(version, t, 32); version[32] = 0;
            ULONGLONG o = 0; for (int i = 7; i >= 0; i--) o = (o << 8) | t[32 + i];
            *offset = o; *end = (ULONGLONG)size.QuadPart - 48;
            ok = o < *end;
        }
    }
    CloseHandle(h);
    return ok;
}

/* Copies the appended zip into its own file. */
static BOOL extractZip(const wchar_t *zip, ULONGLONG from, ULONGLONG to) {
    HANDLE in = CreateFileW(self, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (in == INVALID_HANDLE_VALUE) return FALSE;
    HANDLE out = CreateFileW(zip, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    if (out == INVALID_HANDLE_VALUE) { CloseHandle(in); return FALSE; }
    LARGE_INTEGER pos; pos.QuadPart = (LONGLONG)from;
    SetFilePointerEx(in, pos, NULL, FILE_BEGIN);
    static char buf[1 << 20];
    ULONGLONG left = to - from; BOOL ok = TRUE;
    while (left > 0 && ok) {
        DWORD want = left > sizeof buf ? (DWORD)sizeof buf : (DWORD)left, n = 0, w = 0;
        ok = ReadFile(in, buf, want, &n, NULL) && n > 0 && WriteFile(out, buf, n, &w, NULL) && w == n;
        left -= n;
    }
    CloseHandle(in); CloseHandle(out);
    return ok;
}

/* Unzips with Windows' own tar.exe (Windows 10 1803+), or PowerShell on older Windows. */
static BOOL unzip(const wchar_t *zip, const wchar_t *into) {
    wchar_t sys[MAX_PATH], cmd[4096];
    GetSystemDirectoryW(sys, MAX_PATH);
    CreateDirectoryW(into, NULL);
    swprintf(cmd, 4096, L"\"%ls\\tar.exe\" -xf \"%ls\" -C \"%ls\"", sys, zip, into);
    if (run(cmd, into) == 0) return TRUE;
    swprintf(cmd, 4096, L"\"%ls\\WindowsPowerShell\\v1.0\\powershell.exe\" -NoProfile -ExecutionPolicy Bypass -Command "
             L"\"Expand-Archive -LiteralPath '%ls' -DestinationPath '%ls' -Force\"", sys, zip, into);
    return run(cmd, into) == 0;
}

/* Makes (or remakes) a shortcut that starts the app. */
static void shortcut(const wchar_t *lnk, const wchar_t *javaw, const wchar_t *args, const wchar_t *icon) {
    IShellLinkW *sl = NULL;
    if (FAILED(CoCreateInstance(&CLSID_ShellLink, NULL, CLSCTX_INPROC_SERVER, &IID_IShellLinkW, (void **)&sl))) return;
    sl->lpVtbl->SetPath(sl, javaw);
    sl->lpVtbl->SetArguments(sl, args);
    sl->lpVtbl->SetWorkingDirectory(sl, dest);
    sl->lpVtbl->SetIconLocation(sl, icon, 0);
    sl->lpVtbl->SetDescription(sl, L"White Bag Puppy");
    IPersistFile *pf = NULL;
    if (SUCCEEDED(sl->lpVtbl->QueryInterface(sl, &IID_IPersistFile, (void **)&pf))) {
        pf->lpVtbl->Save(pf, lnk, TRUE);
        pf->lpVtbl->Release(pf);
    }
    sl->lpVtbl->Release(sl);
}

/* Data files worth bringing over from an old White Bag Puppy folder. */
static BOOL isData(const wchar_t *name, BOOL dir) {
    if (dir) {
        const wchar_t *dirs[] = {L"backups", L"outbox", L"sounds", NULL};
        for (int i = 0; dirs[i]; i++) if (_wcsicmp(name, dirs[i]) == 0) return TRUE;
        return FALSE;
    }
    if (_wcsicmp(name, L"README.txt") == 0 || _wcsicmp(name, L"debug-start.txt") == 0) return FALSE;
    const wchar_t *dot = wcsrchr(name, L'.');
    if (!dot) return FALSE;
    const wchar_t *exts[] = {L".properties", L".txt", L".png", L".json", L".csv", NULL};
    for (int i = 0; exts[i]; i++) if (_wcsicmp(dot, exts[i]) == 0) return TRUE;
    return FALSE;
}

/* If this exe sits in an old White Bag Puppy folder, copy its settings and history across (once). */
static int migrate(void) {
    wchar_t oldProps[MAX_PATH], newProps[MAX_PATH], pattern[MAX_PATH];
    join(oldProps, selfDir, L"whitebag-notifier.properties");
    join(newProps, dest, L"whitebag-notifier.properties");
    if (_wcsicmp(selfDir, dest) == 0 || !exists(oldProps)) return 0;
    /* once the pup has logged anything here, this folder is the real one: leave it alone */
    wchar_t used[MAX_PATH];
    join(used, dest, L"loot-log.txt");
    if (exists(used)) return 0;
    DeleteFileW(newProps); /* settings from a first start with nothing in it yet */
    join(pattern, selfDir, L"*");
    WIN32_FIND_DATAW fd; HANDLE h = FindFirstFileW(pattern, &fd);
    if (h == INVALID_HANDLE_VALUE) return 0;
    int n = 0;
    do {
        if (wcscmp(fd.cFileName, L".") == 0 || wcscmp(fd.cFileName, L"..") == 0) continue;
        BOOL dir = (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
        if (!isData(fd.cFileName, dir)) continue;
        wchar_t from[MAX_PATH], to[MAX_PATH];
        join(from, selfDir, fd.cFileName); join(to, dest, fd.cFileName);
        if (exists(to)) continue;
        if (dir ? shellOp(FO_COPY, from, dest) : CopyFileW(from, to, TRUE)) n++;
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    return n;
}

/* Puts the new files in place: Java if it changed, the app if this one is newer than what's there. */
static BOOL install(const char *version) {
    wchar_t temp[MAX_PATH], zip[MAX_PATH], fresh[MAX_PATH], a[MAX_PATH], b[MAX_PATH];
    char x[4096], y[4096];
    GetTempPathW(MAX_PATH, temp);
    swprintf(zip, MAX_PATH, L"%lswbp-payload-%lu.zip", temp, GetCurrentProcessId());
    join(fresh, dest, L".new");
    shellOp(FO_DELETE, fresh, NULL);
    char v[33]; ULONGLONG off, end;
    if (!payload(v, &off, &end) || !extractZip(zip, off, end)) { fail(L"Couldn't read the app inside this file. Download it again?"); return FALSE; }
    if (!unzip(zip, fresh)) { DeleteFileW(zip); fail(L"Couldn't unpack the app."); return FALSE; }
    DeleteFileW(zip);

    /* Java: replace it only when it's a different version (it can't be replaced while the puppy is open) */
    join(a, fresh, L"runtime\\release"); join(b, dest, L"runtime\\release");
    if (!readFileText(b, y, sizeof y) || !readFileText(a, x, sizeof x) || strcmp(x, y) != 0) {
        join(b, dest, L"runtime");
        if (exists(b)) {
            /* moving it aside fails while the puppy is running from it, so nothing is half-deleted */
            wchar_t old[MAX_PATH];
            swprintf(old, MAX_PATH, L"%ls\runtime-old-%lu", dest, GetTickCount());
            if (!MoveFileW(b, old)) {
                shellOp(FO_DELETE, fresh, NULL);
                fail(L"White Bag Puppy is still open. Close it completely, then open this again.");
                return FALSE;
            }
            shellOp(FO_DELETE, old, NULL);
        }
        join(a, fresh, L"runtime");
        if (!MoveFileW(a, b)) { shellOp(FO_DELETE, fresh, NULL); fail(L"Couldn't put Java in place."); return FALSE; }
    }
    /* the app: keep an update the puppy downloaded itself if it's newer than this one */
    join(a, fresh, L"WhiteBagPuppy.jar"); join(b, dest, L"WhiteBagPuppy.jar");
    if (!exists(b) || mtime(a) > mtime(b)) {
        if (!CopyFileW(a, b, FALSE)) {
            shellOp(FO_DELETE, fresh, NULL);
            fail(L"White Bag Puppy is still open. Close it completely, then open this again.");
            return FALSE;
        }
    }
    const wchar_t *small[] = {L"pup.ico", L"README.txt", NULL};
    for (int i = 0; small[i]; i++) { join(a, fresh, small[i]); join(b, dest, small[i]); CopyFileW(a, b, FALSE); }
    shellOp(FO_DELETE, fresh, NULL);
    join(b, dest, L"installed.txt");
    writeFileText(b, version);
    return TRUE;
}

/*
 * Npcap (the free driver that lets the pup see the game's traffic) can't be bundled: its licence doesn't allow it.
 * Returns TRUE to go on and start the pup, FALSE if the person went to install it first.
 */
static BOOL checkNpcap(void) {
    wchar_t sys[MAX_PATH], npcap[MAX_PATH], compat[MAX_PATH];
    GetSystemDirectoryW(sys, MAX_PATH);
    join(npcap, sys, L"Npcap\\wpcap.dll");
    join(compat, sys, L"wpcap.dll");
    if (exists(compat)) return TRUE; /* Npcap in WinPcap-compatible mode (or old WinPcap) */
    const wchar_t *msg = exists(npcap)
        ? L"Npcap is installed, but without \"WinPcap API-compatible mode\", so the pup can't see the game.\n\n"
          L"Run the Npcap installer again and tick \"Install Npcap in WinPcap API-compatible Mode\", then open White Bag Puppy again.\n\n"
          L"Open the Npcap download page now?"
        : L"White Bag Puppy needs Npcap, a free Windows driver that lets it see the game's traffic (Wireshark uses it too). "
          L"It isn't installed yet, and its licence doesn't let the pup include it.\n\n"
          L"Download it from npcap.com, and when installing tick \"Install Npcap in WinPcap API-compatible Mode\". "
          L"Then open White Bag Puppy again.\n\n"
          L"Open the Npcap download page now?\n(No = start the pup anyway)";
    if (MessageBoxW(NULL, msg, APP, MB_YESNO | MB_ICONINFORMATION) == IDYES) {
        ShellExecuteW(NULL, L"open", L"https://npcap.com/#download", NULL, NULL, SW_SHOWNORMAL);
        return FALSE;
    }
    return TRUE;
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR cmdLine, int show) {
    (void)inst; (void)prev; (void)show;
    GetModuleFileNameW(NULL, self, MAX_PATH);
    wcscpy(selfDir, self);
    wchar_t *slash = wcsrchr(selfDir, L'\\'); if (slash) *slash = 0;

    wchar_t local[MAX_PATH];
    if (!GetEnvironmentVariableW(L"LOCALAPPDATA", local, MAX_PATH)) { fail(L"Can't find your AppData folder."); return 1; }
    join(dest, local, L"WhiteBagPuppy");
    CreateDirectoryW(dest, NULL);

    char version[33]; ULONGLONG off, end;
    if (!payload(version, &off, &end)) { fail(L"This file is incomplete. Download White Bag Puppy.exe again."); return 1; }

    wchar_t javaw[MAX_PATH], jar[MAX_PATH], mark[MAX_PATH], icon[MAX_PATH];
    join(javaw, dest, L"runtime\\bin\\javaw.exe");
    join(jar, dest, L"WhiteBagPuppy.jar");
    join(mark, dest, L"installed.txt");
    join(icon, dest, L"pup.ico");
    char had[64] = {0};
    BOOL fresh = !exists(mark);
    if (!readFileText(mark, had, sizeof had) || strcmp(had, version) != 0 || !exists(javaw) || !exists(jar)) {
        if (!install(version)) return 1;
    }
    int moved = migrate();

    /* shortcuts on the Desktop and in the Start menu (made each time, so they always point here) */
    CoInitialize(NULL);
    wchar_t folder[MAX_PATH], lnk[MAX_PATH], args[MAX_PATH * 2];
    swprintf(args, MAX_PATH * 2, L"--enable-native-access=ALL-UNNAMED -jar \"%ls\"", jar);
    if (SUCCEEDED(SHGetFolderPathW(NULL, CSIDL_DESKTOPDIRECTORY, NULL, 0, folder))) {
        join(lnk, folder, APP L".lnk"); shortcut(lnk, javaw, args, icon);
    }
    if (SUCCEEDED(SHGetFolderPathW(NULL, CSIDL_PROGRAMS, NULL, 0, folder))) {
        join(lnk, folder, APP L".lnk"); shortcut(lnk, javaw, args, icon);
    }
    CoUninitialize();

    if (fresh) {
        wchar_t msg[2048];
        swprintf(msg, 2048, L"White Bag Puppy is installed in\n%ls\n\nThere's a White Bag Puppy shortcut on your Desktop and in the Start menu: "
                 L"use that from now on. You can delete this file.%ls", dest,
                 moved ? L"\n\nYour settings and history were copied over from this folder." : L"");
        MessageBoxW(NULL, msg, APP, MB_OK | MB_ICONINFORMATION);
    }

    if (!checkNpcap()) return 0;

    /* start it */
    wchar_t cmd[8192];
    swprintf(cmd, 8192, L"\"%ls\" %ls %ls", javaw, args, cmdLine ? cmdLine : L"");
    STARTUPINFOW si; PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof si); si.cb = sizeof si;
    if (!CreateProcessW(NULL, cmd, NULL, NULL, FALSE, 0, NULL, dest, &si, &pi)) {
        fail(L"Couldn't start White Bag Puppy.");
        return 1;
    }
    CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
    return 0;
}
