#requires -Version 5.1
param(
    [Parameter(Mandatory = $true)]
    [string]$Out
)

Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @"
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public class SnapResult {
    public IntPtr Hwnd;
    public int Width;
    public int Height;
    public bool Restored;
}

public static class WinSnap {
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);

    const int SW_RESTORE = 9;
    const int DWMWA_EXTENDED_FRAME_BOUNDS = 9;
    const uint PW_RENDERFULLCONTENT = 2;
    const int TinyEdge = 50;

    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);
    [DllImport("user32.dll")] public static extern int GetWindowTextLength(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdc, uint flags);
    [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr hwnd, int dwAttribute, out RECT pvAttribute, int cbAttribute);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    sealed class Finder {
        readonly uint[] _pids;
        public IntPtr Best = IntPtr.Zero;
        public readonly EnumProc Callback;

        public Finder(uint[] pids) {
            _pids = pids;
            Callback = OnTop;
        }

        bool Owns(uint pid) {
            for (int i = 0; i < _pids.Length; i++)
                if (_pids[i] == pid) return true;
            return false;
        }

        static string TitleOf(IntPtr hWnd) {
            int len = GetWindowTextLength(hWnd);
            if (len <= 0) return "";
            var sb = new StringBuilder(len + 1);
            GetWindowText(hWnd, sb, sb.Capacity);
            return sb.ToString();
        }

        bool OnTop(IntPtr hWnd, IntPtr lParam) {
            uint pid;
            GetWindowThreadProcessId(hWnd, out pid);
            if (!Owns(pid)) return true;
            if (!string.Equals(TitleOf(hWnd), "Berloga-AI", StringComparison.Ordinal)) return true;
            Best = hWnd;
            return false;
        }
    }

    static bool TrySize(IntPtr hwnd, out int w, out int h) {
        w = 0;
        h = 0;
        RECT dwm;
        int hr = DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, out dwm, Marshal.SizeOf(typeof(RECT)));
        if (hr == 0) {
            w = dwm.Right - dwm.Left;
            h = dwm.Bottom - dwm.Top;
            if (w >= TinyEdge && h >= TinyEdge) return true;
        }
        RECT r;
        if (!GetWindowRect(hwnd, out r)) return false;
        w = r.Right - r.Left;
        h = r.Bottom - r.Top;
        return w > 0 && h > 0;
    }

    public static SnapResult Capture(uint[] pids, string path) {
        var finder = new Finder(pids);
        EnumWindows(finder.Callback, IntPtr.Zero);
        if (finder.Best == IntPtr.Zero)
            throw new InvalidOperationException("Berloga-AI top-level window not found");

        bool restored = false;
        if (IsIconic(finder.Best)) {
            ShowWindow(finder.Best, SW_RESTORE);
            restored = true;
            Thread.Sleep(500);
        }

        int w, h;
        if (!TrySize(finder.Best, out w, out h))
            throw new InvalidOperationException("Could not read window size");

        using (var bmp = new Bitmap(w, h, PixelFormat.Format32bppArgb))
        using (var g = Graphics.FromImage(bmp)) {
            IntPtr hdc = g.GetHdc();
            try {
                if (!PrintWindow(finder.Best, hdc, PW_RENDERFULLCONTENT))
                    throw new InvalidOperationException("PrintWindow failed");
            } finally { g.ReleaseHdc(hdc); }
            bmp.Save(path, ImageFormat.Png);
        }

        return new SnapResult {
            Hwnd = finder.Best,
            Width = w,
            Height = h,
            Restored = restored
        };
    }
}
"@

$procs = @(Get-Process -Name 'berloga-launcher' -ErrorAction SilentlyContinue)
if ($procs.Count -eq 0) { throw 'berloga-launcher process not found' }
$pids = [uint32[]]($procs | ForEach-Object { [uint32]$_.Id })
$result = [WinSnap]::Capture($pids, $Out)
$hwnd = '0x{0:X}' -f $result.Hwnd.ToInt64()
Write-Output ("hwnd={0} size={1}x{2}" -f $hwnd, $result.Width, $result.Height)
