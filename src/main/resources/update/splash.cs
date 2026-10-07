using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

public static class AwUpdateSplash
{
    [DllImport("user32.dll")]
    private static extern bool SetProcessDPIAware();

    public static int Run(int launcher, string installer, string arguments, string app, string assets,
                          string version, string status, string failure, string ready)
    {
        Installation job = new Installation(launcher, installer, arguments);
        SplashForm form = null;
        try
        {
            SetProcessDPIAware();
            Application.EnableVisualStyles();
            form = new SplashForm(new SplashLook(assets, version, status), job, app, failure, ready);
        }
        catch (Exception)
        {
            form = null;
        }

        if (form != null)
        {
            try
            {
                Application.Run(form);
            }
            catch (Exception)
            {
            }
        }
        if (!job.Started)
        {
            Touch(ready);
            job.Run();
        }
        while (!job.Done) Thread.Sleep(200);
        if (form == null || !form.Launched) StartApp(app);
        return job.ExitCode;
    }

    public static void Render(string assets, string version, string status, string output, float scale, bool failed)
    {
        SplashLook look = new SplashLook(assets, version, status);
        look.Failed = failed;
        int width = (int)(SplashLook.Width * scale);
        int height = (int)(SplashLook.Height * scale);
        using (Bitmap bitmap = new Bitmap(width, height))
        using (Graphics graphics = Graphics.FromImage(bitmap))
        {
            look.Paint(graphics, width, height, scale, 0.45f);
            bitmap.Save(output, ImageFormat.Png);
        }
    }

    internal static void Touch(string path)
    {
        try
        {
            File.WriteAllText(path, "");
        }
        catch (Exception)
        {
        }
    }

    internal static bool StartApp(string app)
    {
        if (string.IsNullOrEmpty(app)) return false;
        try
        {
            ProcessStartInfo info = new ProcessStartInfo(app);
            info.WorkingDirectory = Path.GetDirectoryName(app);
            info.UseShellExecute = true;
            Process.Start(info);
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    internal static bool AppWindowShown(string app)
    {
        if (string.IsNullOrEmpty(app)) return true;
        foreach (Process process in Process.GetProcessesByName(Path.GetFileNameWithoutExtension(app)))
        {
            try
            {
                if (process.MainWindowHandle != IntPtr.Zero) return true;
            }
            catch (Exception)
            {
            }
            finally
            {
                process.Dispose();
            }
        }
        return false;
    }
}

internal sealed class Installation
{
    private readonly int launcher;
    private readonly string installer;
    private readonly string arguments;
    private volatile bool started;
    private volatile bool done;
    private volatile int exitCode = -1;

    public Installation(int launcher, string installer, string arguments)
    {
        this.launcher = launcher;
        this.installer = installer;
        this.arguments = arguments;
    }

    public bool Started { get { return started; } }
    public bool Done { get { return done; } }
    public int ExitCode { get { return exitCode; } }
    public bool Succeeded { get { return exitCode == 0 || exitCode == 3010; } }

    public void Start()
    {
        started = true;
        Thread thread = new Thread(Run);
        thread.IsBackground = true;
        thread.Start();
    }

    public void Run()
    {
        started = true;
        try
        {
            try
            {
                using (Process previous = Process.GetProcessById(launcher))
                {
                    if (!previous.WaitForExit(60000))
                    {
                        exitCode = 1618;
                        return;
                    }
                }
            }
            catch (Exception)
            {
            }
            ProcessStartInfo info = new ProcessStartInfo(installer, arguments);
            info.UseShellExecute = false;
            info.CreateNoWindow = true;
            using (Process process = Process.Start(info))
            {
                process.WaitForExit();
                exitCode = process.ExitCode;
            }
        }
        catch (Exception)
        {
            exitCode = -1;
        }
        finally
        {
            done = true;
        }
    }
}

internal sealed class SplashLook
{
    public const int Width = 520;
    public const int Height = 180;

    private static readonly Color Surface = Color.FromArgb(0x10, 0x11, 0x12);
    private static readonly Color Track = Color.FromArgb(0x24, 0x26, 0x29);
    private static readonly Color Accent = Color.FromArgb(0x2B, 0xC6, 0x7C);
    private static readonly Color AccentPressed = Color.FromArgb(0x23, 0xAB, 0x68);
    private static readonly Color Text = Color.FromArgb(0xEE, 0xEC, 0xF3);
    private static readonly Color Muted = Color.FromArgb(0x8F, 0x8B, 0x9C);
    private static readonly Color Danger = Color.FromArgb(0xF5, 0x8E, 0x8E);
    private const float Sweep = 0.35f;

    private readonly List<PrivateFontCollection> collections = new List<PrivateFontCollection>();
    private readonly Image wordmark;
    private readonly FontFamily regular;
    private readonly FontFamily bold;
    private readonly string version;

    public string Status;
    public bool Failed;

    public SplashLook(string assets, string version, string status)
    {
        wordmark = Image.FromFile(Path.Combine(assets, "wordmark.png"));
        regular = Family(Path.Combine(assets, "Onest-Regular.ttf"));
        bold = Family(Path.Combine(assets, "Onest-Bold.ttf"));
        this.version = "v" + version;
        Status = status;
    }

    public static Color Background { get { return Surface; } }

    public void Paint(Graphics g, int width, int height, float scale, float phase)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        g.Clear(Surface);

        float pad = 26 * scale;
        float markWidth = 312 * scale;
        g.DrawImage(wordmark, pad, pad, markWidth, markWidth * wordmark.Height / wordmark.Width);

        StringFormat typographic = StringFormat.GenericTypographic;
        using (Font small = MakeFont(bold, 11 * scale))
        using (Brush muted = new SolidBrush(Muted))
        {
            float spacing = 0.9f * scale;
            float[] advances = new float[version.Length];
            float total = 0;
            for (int i = 0; i < version.Length; i++)
            {
                advances[i] = g.MeasureString(version[i].ToString(), small, PointF.Empty, typographic).Width + spacing;
                total += advances[i];
            }
            float x = width - pad - total + spacing;
            for (int i = 0; i < version.Length; i++)
            {
                g.DrawString(version[i].ToString(), small, muted, x, pad, typographic);
                x += advances[i];
            }
        }

        float barHeight = 8 * scale;
        float barTop = height - pad - 24 * scale - barHeight;
        using (Font body = MakeFont(regular, 14 * scale))
        using (Brush brush = new SolidBrush(Failed ? Danger : Text))
        {
            float line = body.GetHeight(g);
            g.DrawString(Status, body, brush, pad, barTop - 10 * scale - line, typographic);
        }

        RectangleF track = new RectangleF(pad, barTop, width - 2 * pad, barHeight);
        using (GraphicsPath trackPath = Pill(track))
        using (Brush trackBrush = new SolidBrush(Track))
        {
            g.FillPath(trackBrush, trackPath);
            if (Failed) return;
            float eased = phase < 0.5f ? 4 * phase * phase * phase : 1 - (float)Math.Pow(-2 * phase + 2, 3) / 2;
            float left = track.X + track.Width * (-Sweep + (1 + Sweep) * eased);
            RectangleF segment = new RectangleF(left, track.Y, track.Width * Sweep, track.Height);
            Region clip = g.Clip;
            g.SetClip(trackPath);
            using (GraphicsPath segmentPath = Pill(segment))
            using (LinearGradientBrush fill = new LinearGradientBrush(
                       new RectangleF(segment.X - 1, segment.Y, segment.Width + 2, segment.Height), AccentPressed, AccentPressed, 0f))
            {
                ColorBlend blend = new ColorBlend(3);
                blend.Colors = new Color[] { AccentPressed, Accent, AccentPressed };
                blend.Positions = new float[] { 0f, 0.5f, 1f };
                fill.InterpolationColors = blend;
                g.FillPath(fill, segmentPath);
            }
            g.Clip = clip;
        }
    }

    private static GraphicsPath Pill(RectangleF bounds)
    {
        float d = Math.Min(bounds.Height, bounds.Width);
        GraphicsPath path = new GraphicsPath();
        path.AddArc(bounds.X, bounds.Y, d, d, 90, 180);
        path.AddArc(bounds.Right - d, bounds.Y, d, d, 270, 180);
        path.CloseFigure();
        return path;
    }

    private FontFamily Family(string file)
    {
        try
        {
            PrivateFontCollection collection = new PrivateFontCollection();
            collection.AddFontFile(file);
            collections.Add(collection);
            return collection.Families[0];
        }
        catch (Exception)
        {
            return new FontFamily("Segoe UI");
        }
    }

    private static Font MakeFont(FontFamily family, float pixels)
    {
        FontStyle style = family.IsStyleAvailable(FontStyle.Regular) ? FontStyle.Regular : FontStyle.Bold;
        return new Font(family, pixels, style, GraphicsUnit.Pixel);
    }
}

internal sealed class SplashForm : Form
{
    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    private const int CornerPreference = 33;
    private const int BorderColor = 34;
    private const int Round = 2;
    private const int Outline = 0x00352D2E;
    private const long CycleMillis = 1300;
    private const long FailureMillis = 3500;
    private const long WindowWaitMillis = 15000;
    private const long LookupMillis = 250;

    private readonly SplashLook look;
    private readonly Installation job;
    private readonly string app;
    private readonly string failure;
    private readonly string ready;
    private readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();
    private readonly Stopwatch clock = Stopwatch.StartNew();
    private readonly float scale;
    private long finishedAt = -1;
    private long launchedAt = -1;
    private long lookedAt = -1;

    public SplashForm(SplashLook look, Installation job, string app, string failure, string ready)
    {
        this.look = look;
        this.job = job;
        this.app = app;
        this.failure = failure;
        this.ready = ready;

        using (Graphics screen = Graphics.FromHwnd(IntPtr.Zero))
        {
            scale = screen.DpiX / 96f;
        }
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.Manual;
        ShowInTaskbar = true;
        TopMost = true;
        Text = "AWLauncher";
        BackColor = SplashLook.Background;
        ClientSize = new Size((int)(SplashLook.Width * scale), (int)(SplashLook.Height * scale));
        Rectangle area = Screen.FromPoint(Cursor.Position).WorkingArea;
        Location = new Point(area.Left + (area.Width - Width) / 2, area.Top + (area.Height - Height) / 2);
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint, true);
        try
        {
            Icon = Icon.ExtractAssociatedIcon(app);
        }
        catch (Exception)
        {
        }
        timer.Interval = 15;
        timer.Tick += OnTick;
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        try
        {
            int round = Round;
            DwmSetWindowAttribute(Handle, CornerPreference, ref round, 4);
            int outline = Outline;
            DwmSetWindowAttribute(Handle, BorderColor, ref outline, 4);
        }
        catch (Exception)
        {
        }
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        AwUpdateSplash.Touch(ready);
        job.Start();
        timer.Start();
        Activate();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!job.Done && e.CloseReason == CloseReason.UserClosing) e.Cancel = true;
        base.OnFormClosing(e);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        float phase = (clock.ElapsedMilliseconds % CycleMillis) / (float)CycleMillis;
        look.Paint(e.Graphics, ClientSize.Width, ClientSize.Height, scale, phase);
    }

    public bool Launched { get; private set; }

    private void OnTick(object sender, EventArgs e)
    {
        try
        {
            Advance();
        }
        catch (Exception)
        {
            Finish();
        }
    }

    private void Advance()
    {
        Invalidate();
        long now = clock.ElapsedMilliseconds;
        if (finishedAt < 0 && job.Done)
        {
            finishedAt = now;
            if (!job.Succeeded)
            {
                look.Failed = true;
                look.Status = failure.Replace("{0}", job.ExitCode.ToString());
            }
        }
        if (finishedAt < 0) return;

        if (launchedAt < 0 && (job.Succeeded || now - finishedAt >= FailureMillis))
        {
            launchedAt = now;
            Launched = AwUpdateSplash.StartApp(app);
            if (!Launched) Finish();
            return;
        }
        if (launchedAt < 0 || now - lookedAt < LookupMillis) return;
        lookedAt = now;
        if (now - launchedAt > WindowWaitMillis || AwUpdateSplash.AppWindowShown(app)) Finish();
    }

    private void Finish()
    {
        timer.Stop();
        Close();
    }
}
