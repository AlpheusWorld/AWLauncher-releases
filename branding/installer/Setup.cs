using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

public static class AwSetup
{
    [STAThread]
    public static int Main(string[] args)
    {
        Native.SetProcessDPIAware();
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(true);
        try
        {
            if (args.Length == 2 && args[0] == "--extract")
            {
                using (Stream source = Resource("Payload"))
                using (FileStream target = new FileStream(args[1], FileMode.CreateNew, FileAccess.Write, FileShare.None)) source.CopyTo(target);
                return 0;
            }
            using (SetupAssets assets = new SetupAssets())
            {
                if (args.Length == 2 && args[0] == "--test-extract")
                {
                    string target = Path.GetFullPath(args[1]); Directory.CreateDirectory(target);
                    using (InstallJob job = new InstallJob(assets, target, false, false))
                    {
                        uint code = job.Run(true);
                        File.WriteAllText(Path.Combine(target, "setup-extract-result.txt"), code.ToString(CultureInfo.InvariantCulture));
                        return (int)code;
                    }
                }
                float? renderScale = null;
                if (args.Length == 4 && args[0] == "--render")
                {
                    float value;
                    if (!float.TryParse(args[3], NumberStyles.Float, CultureInfo.InvariantCulture, out value) ||
                        float.IsNaN(value) || float.IsInfinity(value) || value < 0.75f || value > 3f)
                        throw new ArgumentException("Render scale must be between 0.75 and 3");
                    renderScale = value;
                }
                using (SetupForm form = new SetupForm(assets, args.Length > 0, renderScale))
                {
                    if ((args.Length == 3 || args.Length == 4) && args[0] == "--render")
                    {
                        form.Preview(args[1]);
                        form.Show();
                        Application.DoEvents();
                        using (Bitmap image = new Bitmap(form.ClientSize.Width, form.ClientSize.Height))
                        {
                            form.DrawToBitmap(image, form.ClientRectangle);
                            image.Save(args[2], ImageFormat.Png);
                        }
                        form.Close();
                        return 0;
                    }
                    if (args.Length != 0) return 2;
                    Application.Run(form);
                }
            }
            return 0;
        }
        catch (Exception error)
        {
            MessageBox.Show("Не удалось открыть установщик: " + error.Message, "AWLauncher", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    internal static Stream Resource(string name)
    {
        Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("AWSetup." + name);
        if (stream == null) throw new IOException("Missing installer resource: " + name);
        return stream;
    }
}

internal sealed class SetupAssets : IDisposable
{
    internal readonly string Version, ProductCode, UpgradeCode, PayloadHash;
    internal readonly long AppBytes;
    internal readonly Image Mark;
    private readonly List<PrivateFontCollection> collections = new List<PrivateFontCollection>();
    private readonly List<IntPtr> memory = new List<IntPtr>();
    private readonly List<IntPtr> fontHandles = new List<IntPtr>();
    private readonly List<Font> fonts = new List<Font>();
    private readonly FontFamily regular, bold;

    internal SetupAssets()
    {
        using (Stream stream = AwSetup.Resource("Metadata"))
        using (StreamReader reader = new StreamReader(stream))
        {
            Version = reader.ReadLine(); ProductCode = reader.ReadLine(); UpgradeCode = reader.ReadLine();
            AppBytes = long.Parse(reader.ReadLine(), CultureInfo.InvariantCulture);
            PayloadHash = reader.ReadLine();
            if (PayloadHash == null || !System.Text.RegularExpressions.Regex.IsMatch(PayloadHash, "^[a-f0-9]{64}$")) throw new IOException("Invalid installer metadata");
        }
        using (Stream stream = AwSetup.Resource("Mark"))
        using (Image original = Image.FromStream(stream)) Mark = new Bitmap(original);
        regular = LoadFont("Regular"); bold = LoadFont("Bold");
    }

    private FontFamily LoadFont(string name)
    {
        using (Stream stream = AwSetup.Resource(name))
        using (MemoryStream buffer = new MemoryStream())
        {
            stream.CopyTo(buffer);
            byte[] bytes = buffer.ToArray();
            IntPtr pointer = Marshal.AllocHGlobal(bytes.Length);
            memory.Add(pointer);
            Marshal.Copy(bytes, 0, pointer, bytes.Length);
            uint count;
            IntPtr handle = Native.AddFontMemResourceEx(pointer, (uint)bytes.Length, IntPtr.Zero, out count);
            if (handle != IntPtr.Zero) fontHandles.Add(handle);
            PrivateFontCollection collection = new PrivateFontCollection();
            collections.Add(collection);
            collection.AddMemoryFont(pointer, bytes.Length);
            return collection.Families[0];
        }
    }

    internal Font Font(float size, bool strong, float scale)
    {
        FontFamily family = strong ? bold : regular;
        FontStyle style = family.IsStyleAvailable(FontStyle.Regular) ? FontStyle.Regular : FontStyle.Bold;
        Font font = new Font(family, size * scale, style, GraphicsUnit.Pixel);
        fonts.Add(font);
        return font;
    }

    public void Dispose()
    {
        foreach (Font font in fonts) font.Dispose();
        Mark.Dispose();
        foreach (PrivateFontCollection collection in collections) collection.Dispose();
        foreach (IntPtr handle in fontHandles) Native.RemoveFontMemResourceEx(handle);
        foreach (IntPtr pointer in memory) Marshal.FreeHGlobal(pointer);
    }
}

internal sealed class SetupForm : Form
{
    internal static readonly Color Surface = Color.FromArgb(20, 20, 20);
    internal static readonly Color Accent = Color.FromArgb(50, 200, 121);
    internal static readonly Color Ink = Color.FromArgb(248, 249, 250);
    internal static readonly Color Muted = Color.FromArgb(158, 160, 164);
    private readonly SetupAssets assets;
    private readonly float scale;
    private readonly bool preview;
    private readonly Label title, detail;
    private readonly SetupButton primary, options, close, minimize, back, browse;
    private readonly Panel settings;
    private readonly TextBox directory;
    private readonly CheckBox shortcut, launch;
    private readonly System.Windows.Forms.Timer animation = new System.Windows.Forms.Timer();
    private InstallJob job;
    private string stage = "welcome", installedProduct, installedDirectory;
    private double displayedProgress;
    private bool confirmedDirectory;

    internal SetupForm(SetupAssets assets, bool preview, float? renderScale = null)
    {
        this.assets = assets; this.preview = preview;
        using (Graphics screen = Graphics.FromHwnd(IntPtr.Zero)) scale = screen.DpiX / 96f;
        Rectangle workArea = Screen.FromPoint(Cursor.Position).WorkingArea;
        scale = Math.Min(renderScale ?? scale, Math.Min((workArea.Width - 32) / 600f, (workArea.Height - 32) / 600f));
        FormBorderStyle = FormBorderStyle.None;
        AutoScaleMode = AutoScaleMode.None;
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = MaximumSize = new Size(P(600), P(600));
        ClientSize = new Size(P(600), P(600));
        MaximizeBox = false;
        Text = "Установка AWLauncher " + assets.Version;
        BackColor = Surface;
        DoubleBuffered = true;
        KeyPreview = true;
        Icon = Icon.ExtractAssociatedIcon(Assembly.GetExecutingAssembly().Location);
        title = Label("AWLauncher", 0, 330, 600, 42, 28, true, Ink);
        detail = Label("", 64, 394, 472, 58, 14, false, Muted);
        primary = Button("Установить", 240, 490, 120, 44, true);
        options = Button("Настройки установки", 190, 544, 220, 28, false);
        options.Link = true; options.ForeColor = Muted;
        close = Button("×", 548, 8, 40, 32, false);
        close.AccessibleName = "Закрыть установщик";
        close.Link = true;
        minimize = Button("−", 504, 8, 40, 32, false);
        minimize.AccessibleName = "Свернуть установщик";
        minimize.Link = true;
        minimize.Click += delegate { WindowState = FormWindowState.Minimized; };
        back = Button("Назад", 28, 13, 90, 36, false);
        back.Link = true; back.Visible = false;
        close.Click += delegate { Close(); };
        options.Click += delegate { SetStage("options"); };
        back.Click += delegate { SetStage("welcome"); };
        primary.Click += MainAction;
        AcceptButton = primary;
        settings = new Panel { BackColor = Surface, Bounds = new Rectangle(P(64), P(204), P(472), P(145)), Visible = false };
        Controls.Add(settings);
        Label pathLabel = Label("Папка лаунчера", 64, 170, 472, 24, 14, false, Muted);
        pathLabel.Name = "PathLabel"; pathLabel.TextAlign = ContentAlignment.MiddleLeft; pathLabel.Visible = false;
        SetupField input = new SetupField { Bounds = new Rectangle(0, 0, P(360), P(42)) };
        settings.Controls.Add(input);
        directory = new TextBox { BorderStyle = BorderStyle.None, BackColor = Color.FromArgb(29, 31, 36), ForeColor = Ink,
            Font = assets.Font(14, false, scale), Bounds = new Rectangle(P(12), P(12), P(336), P(24)),
            Text = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AWLauncher"), AccessibleName = "Папка установки" };
        input.Controls.Add(directory);
        browse = new SetupButton { Text = "Выбрать", Font = assets.Font(13, true, scale), Bounds = new Rectangle(P(376), 0, P(96), P(42)) };
        settings.Controls.Add(browse);
        browse.Click += Browse;
        shortcut = new SetupCheckBox { Text = "Создать ярлык на рабочем столе", Checked = true, ForeColor = Ink,
            BackColor = Surface, UseCompatibleTextRendering = true, Font = assets.Font(14, false, scale),
            Bounds = new Rectangle(0, P(64), P(472), P(30)), AccessibleName = "Создать ярлык на рабочем столе" };
        settings.Controls.Add(shortcut);
        launch = new SetupCheckBox { Text = "Запустить сейчас", Checked = true, Neutral = true, ForeColor = Ink,
            BackColor = Surface, Font = assets.Font(14, false, scale), AccessibleName = "Запустить AWLauncher после установки", Visible = false };
        using (Graphics measurement = Graphics.FromHwnd(IntPtr.Zero))
        {
            int width = P(28) + (int)Math.Ceiling(measurement.MeasureString(launch.Text, launch.Font, int.MaxValue, StringFormat.GenericTypographic).Width);
            launch.Bounds = new Rectangle((ClientSize.Width - width) / 2, P(440), width, P(28));
        }
        Controls.Add(launch);
        directory.TextChanged += delegate { confirmedDirectory = false; };
        animation.Interval = 16;
        animation.Tick += delegate {
            if (job == null) return;
            displayedProgress += (job.Progress - displayedProgress) * 0.18;
            detail.Text = job.Cancelling ? "Отменяем установку…" : job.Status + "… " + (int)(displayedProgress * 100) + "%";
            close.Enabled = job.CanCancel && !job.Cancelling;
            Invalidate(new Rectangle(P(64), P(478), P(472), P(14)));
        };
    }

    private int P(double value) { return (int)Math.Round(value * scale); }
    private Label Label(string text, int x, int y, int w, int h, float size, bool strong, Color color)
    {
        Label label = new SetupLabel { Text = text, Bounds = new Rectangle(P(x), P(y), P(w), P(h)), ForeColor = color,
            BackColor = Color.Transparent, Font = assets.Font(size, strong, scale), TextAlign = ContentAlignment.MiddleCenter,
            UseCompatibleTextRendering = true };
        Controls.Add(label); return label;
    }
    private SetupButton Button(string text, int x, int y, int w, int h, bool main)
    {
        SetupButton button = new SetupButton { Text = text, Bounds = new Rectangle(P(x), P(y), P(w), P(h)), Primary = main,
            Font = assets.Font(main ? 15 : 13, false, scale), AccessibleName = text };
        Controls.Add(button); return button;
    }

    protected override async void OnShown(EventArgs e)
    {
        base.OnShown(e);
        if (preview) return;
        primary.Enabled = false;
        try
        {
            string[] installed = await Task.Run(delegate { return Native.FindInstallation(assets.UpgradeCode, assets.ProductCode); });
            if (IsDisposed) return;
            if (installed != null)
            {
                installedProduct = installed[0]; installedDirectory = installed[1]; directory.Text = installedDirectory;
                primary.Text = "Обновить"; primary.AccessibleName = primary.Text; detail.Text = "Готово к обновлению текущего лаунчера";
            }
        }
        catch (Exception) { if (!IsDisposed) detail.Text = ""; }
        finally { if (!IsDisposed) primary.Enabled = true; }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        try { int corner = 1; Native.DwmSetWindowAttribute(Handle, 33, ref corner, 4); int border = 0x002D2D2D; Native.DwmSetWindowAttribute(Handle, 34, ref border, 4); }
        catch (DllNotFoundException) { }
    }

    protected override void OnMouseDown(MouseEventArgs e)
    {
        base.OnMouseDown(e);
        if (e.Button == MouseButtons.Left && e.Y < P(60)) { Native.ReleaseCapture(); Native.SendMessage(Handle, 0xA1, new IntPtr(2), IntPtr.Zero); }
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.KeyCode == Keys.Escape) { Close(); e.Handled = true; }
        base.OnKeyDown(e);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (stage == "installing" && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            if (job != null && job.CanCancel) { job.Cancel(); close.Enabled = false; }
            return;
        }
        animation.Stop();
        base.OnFormClosing(e);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) { animation.Dispose(); if (job != null) job.Dispose(); }
        base.Dispose(disposing);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        Graphics g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        using (Pen border = new Pen(Color.FromArgb(45, 45, 45))) g.DrawRectangle(border, 0, 0, ClientSize.Width - 1, ClientSize.Height - 1);
        if (stage != "options") g.DrawImage(assets.Mark, new Rectangle(P(232), P(182), P(136), P(136)));
        if (stage == "installing")
        {
            RectangleF track = new RectangleF(P(64), P(482), P(472), P(6));
            using (GraphicsPath path = Rounded(track, P(3)))
            using (Brush brush = new SolidBrush(Color.FromArgb(94, 97, 101))) g.FillPath(brush, path);
            if (displayedProgress > 0)
            {
                RectangleF fill = new RectangleF(track.X, track.Y, (float)(track.Width * Math.Min(1, displayedProgress)), track.Height);
                using (GraphicsPath path = Rounded(fill, Math.Min(fill.Width / 2, P(3))))
                using (Brush brush = new SolidBrush(Ink)) g.FillPath(brush, path);
            }
        }
    }

    internal static GraphicsPath Rounded(RectangleF bounds, float radius)
    {
        GraphicsPath path = new GraphicsPath();
        float d = Math.Min(radius * 2, Math.Min(bounds.Width, bounds.Height));
        if (d <= 0) { path.AddRectangle(bounds); return path; }
        path.AddArc(bounds.X, bounds.Y, d, d, 180, 90); path.AddArc(bounds.Right - d, bounds.Y, d, d, 270, 90);
        path.AddArc(bounds.Right - d, bounds.Bottom - d, d, d, 0, 90); path.AddArc(bounds.X, bounds.Bottom - d, d, d, 90, 90); path.CloseFigure(); return path;
    }

    private void SetStage(string value)
    {
        bool showSettings = value == "options";
        stage = value; settings.Visible = showSettings; Controls["PathLabel"].Visible = showSettings;
        back.Visible = showSettings;
        launch.Visible = value == "success";
        options.Visible = value == "welcome" || value == "confirm" || value == "error";
        options.Text = value == "welcome" ? "Настройки установки" : "Изменить папку установки";
        options.AccessibleName = options.Text;
        primary.Visible = value != "installing";
        title.Bounds = new Rectangle(0, P(showSettings ? 88 : 330), ClientSize.Width, P(42));
        detail.Bounds = new Rectangle(P(64), P(value == "installing" ? 508 : showSettings ? 360 : value == "success" ? 380 : 390), P(472), P(value == "installing" ? 38 : value == "confirm" || value == "error" ? 80 : 58));
        detail.ForeColor = value == "installing" ? Ink : value == "error" ? Color.FromArgb(241, 138, 138) : Muted;
        detail.Visible = value != "options";
        title.Text = showSettings ? "Настройка установки" : "AWLauncher";
        primary.Text = value == "success" ? "Завершить" : value == "error" ? "Повторить" : value == "confirm" ? "Установить в эту папку" : installedProduct != null ? "Обновить" : "Установить";
        int buttonWidth = P(value == "confirm" ? 232 : 120);
        primary.Bounds = new Rectangle((ClientSize.Width - buttonWidth) / 2, P(490), buttonWidth, P(44));
        primary.AccessibleName = primary.Text;
        primary.Enabled = true;
        if (value == "success") { launch.Checked = true; detail.Text = ""; }
        if (value == "confirm") detail.Text = "В выбранной папке уже есть файлы\nУстановка может заменить совпадающие имена";
        Invalidate();
    }

    private void Browse(object sender, EventArgs e)
    {
        try { string selected = Native.ChooseFolder(Handle, directory.Text); if (selected != null) directory.Text = selected; }
        catch (Exception) { detail.Visible = true; detail.Text = "Не удалось открыть выбор папки — укажи путь вручную"; }
    }

    private async void MainAction(object sender, EventArgs e)
    {
        if (stage == "installing")
        {
            if (job != null && job.CanCancel) { job.Cancel(); primary.Enabled = close.Enabled = false; }
            return;
        }
        if (stage == "success")
        {
            if (!launch.Checked) { Close(); return; }
            if (preview) return;
            primary.Enabled = false;
            if (AwUpdateSplash.StartApp(Path.Combine(directory.Text, "AWLauncher.exe"))) Close();
            else { detail.Text = "Не удалось запустить лаунчер — открой его из папки установки"; }
            if (!IsDisposed) primary.Enabled = true;
            return;
        }
        if (stage == "confirm") confirmedDirectory = true;
        primary.Enabled = false;
        string target = directory.Text.Trim();
        bool desktop = shortcut.Checked;
        try
        {
            string path = await Task.Run(delegate { return ValidateTarget(target, assets.AppBytes); });
            bool nonempty = await Task.Run(delegate { return Directory.Exists(path) && Directory.EnumerateFileSystemEntries(path).Any(); });
            if (nonempty && !confirmedDirectory && !string.Equals(path.TrimEnd('\\'), (installedDirectory ?? "").TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
            { directory.Text = path; SetStage("confirm"); return; }
            directory.Text = path;
            if (job != null) job.Dispose();
            job = new InstallJob(assets, path, desktop, string.Equals(installedProduct, assets.ProductCode, StringComparison.OrdinalIgnoreCase));
            displayedProgress = 0;
            SetStage("installing");
            detail.Text = "Подготавливаем установку… 0%";
            animation.Start();
            uint result = await Task.Run(delegate { return job.Run(); });
            animation.Stop(); close.Enabled = true;
            if (result == 0 || result == 3010)
            {
                SetStage("success");
                if (result == 3010) detail.Text = "Лаунчер установлен — Windows может потребовать перезагрузку";
            }
            else if (result == 1602) { SetStage("welcome"); detail.Text = "Установка отменена — можно попробовать снова"; }
            else { SetStage("error"); detail.Text = "Не удалось установить AWLauncher\n" + InstallJob.ErrorMessage(result); }
        }
        catch (Exception failure)
        {
            animation.Stop(); close.Enabled = true;
            SetStage("error"); detail.Text = failure.Message;
        }
        finally { if (!IsDisposed) primary.Enabled = true; }
    }

    private static string ValidateTarget(string value, long appBytes)
    {
        if (string.IsNullOrWhiteSpace(value) || !Path.IsPathRooted(value) || value.Contains('"')) throw new IOException("Укажи полный путь к папке установки");
        string path = Path.GetFullPath(value).TrimEnd('\\');
        string root = Path.GetPathRoot(path);
        if (root.StartsWith("\\\\", StringComparison.Ordinal) || path.Length <= root.Length) throw new IOException("Выбери отдельную папку на локальном диске");
        string data = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".aw");
        if (path.Equals(data, StringComparison.OrdinalIgnoreCase) || path.StartsWith(data + "\\", StringComparison.OrdinalIgnoreCase)) throw new IOException("Выбери папку вне игровых данных .aw");
        if (File.Exists(path)) throw new IOException("По этому пути находится файл — выбери папку");
        if (new DriveInfo(root).AvailableFreeSpace < appBytes + 64L * 1024 * 1024) throw new IOException("На выбранном диске недостаточно свободного места");
        foreach (Process process in Process.GetProcessesByName("AWLauncher"))
        {
            using (process)
            {
                try { if (process.MainModule.FileName.StartsWith(path + "\\", StringComparison.OrdinalIgnoreCase)) throw new IOException("Закрой AWLauncher и попробуй снова"); }
                catch (System.ComponentModel.Win32Exception) { }
                catch (InvalidOperationException) { }
            }
        }
        return path;
    }

    internal void Preview(string value)
    {
        if (value == "options" || value == "success" || value == "confirm") SetStage(value);
        else if (value == "error") { SetStage(value); detail.Text = "Проверь доступ к папке и свободное место, затем попробуй снова"; }
        else if (value == "progress") { job = new InstallJob(assets, directory.Text, true, false); job.Preview(); displayedProgress = job.Progress; SetStage("installing"); detail.Text = job.Status + "… " + (int)(displayedProgress * 100) + "%"; }
        else if (value != "welcome") throw new ArgumentException("Unknown preview state");
    }
}

internal sealed class SetupLabel : Label
{
    protected override void OnPaint(PaintEventArgs e)
    {
        // Explicit grayscale smoothing also works when Windows font smoothing is disabled.
        e.Graphics.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        e.Graphics.TextContrast = 4;
        using (Brush text = new SolidBrush(ForeColor))
        using (StringFormat format = new StringFormat(StringFormat.GenericTypographic))
        {
            format.Alignment = TextAlign == ContentAlignment.MiddleLeft ? StringAlignment.Near : StringAlignment.Center;
            format.LineAlignment = StringAlignment.Center;
            format.Trimming = StringTrimming.EllipsisWord;
            e.Graphics.DrawString(Text, Font, text, ClientRectangle, format);
        }
    }
}

internal sealed class SetupCheckBox : CheckBox
{
    internal bool Neutral;
    internal SetupCheckBox() { SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true); }
    protected override void OnPaint(PaintEventArgs e)
    {
        Graphics g = e.Graphics;
        g.Clear(BackColor); g.SmoothingMode = SmoothingMode.AntiAlias; g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        float size = (float)Math.Round(Math.Max(16, Font.Size * 9 / 7)), y = (Height - size) / 2;
        float gap = Font.Size * 4 / 7;
        using (GraphicsPath shape = SetupForm.Rounded(new RectangleF(1, y, size, size), size / 4))
        using (Brush fill = new SolidBrush(Checked ? Neutral ? SetupForm.Ink : SetupForm.Accent : Color.FromArgb(29, 31, 36)))
        using (Pen outline = new Pen(Focused && ShowFocusCues ? SetupForm.Ink : Checked && Neutral ? SetupForm.Ink : Color.FromArgb(71, 79, 76)))
        { g.FillPath(fill, shape); g.DrawPath(outline, shape); }
        if (Checked)
            using (Pen mark = new Pen(Neutral ? SetupForm.Surface : Color.FromArgb(8, 32, 21), Math.Max(1.5f, size / 9)) { StartCap = LineCap.Round, EndCap = LineCap.Round })
                g.DrawLines(mark, new[] { new PointF(size * .22f, y + size * .52f), new PointF(size * .44f, y + size * .73f), new PointF(size * .78f, y + size * .30f) });
        using (Brush text = new SolidBrush(ForeColor))
        using (StringFormat format = new StringFormat(StringFormat.GenericTypographic) { LineAlignment = StringAlignment.Center })
            g.DrawString(Text, Font, text, new RectangleF(size + gap, 0, Width - size - gap, Height), format);
    }
}

internal sealed class SetupField : Panel
{
    internal SetupField() { BackColor = SetupForm.Surface; DoubleBuffered = true; }
    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        using (GraphicsPath shape = SetupForm.Rounded(new RectangleF(1, 1, Width - 2, Height - 2), Height / 4f))
        using (Brush fill = new SolidBrush(Color.FromArgb(29, 31, 36)))
        using (Pen border = new Pen(Color.FromArgb(51, 57, 60)))
        { e.Graphics.FillPath(fill, shape); e.Graphics.DrawPath(border, shape); }
    }
}

internal sealed class SetupButton : Button
{
    internal bool Primary, Link;
    private bool hovered, pressed;
    internal SetupButton()
    {
        FlatStyle = FlatStyle.Flat; FlatAppearance.BorderSize = 0; BackColor = SetupForm.Surface; ForeColor = SetupForm.Ink; Cursor = Cursors.Hand;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);
    }
    protected override void OnMouseEnter(EventArgs e) { hovered = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { hovered = pressed = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnMouseDown(MouseEventArgs e) { pressed = true; Invalidate(); base.OnMouseDown(e); }
    protected override void OnMouseUp(MouseEventArgs e) { pressed = false; Invalidate(); base.OnMouseUp(e); }
    protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
    protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }
    protected override void OnPaint(PaintEventArgs e)
    {
        Graphics g = e.Graphics; g.Clear(SetupForm.Surface); g.SmoothingMode = SmoothingMode.AntiAlias; g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        Color background = Primary ? (pressed ? Color.FromArgb(218, 221, 224) : hovered ? Color.White : SetupForm.Ink) : pressed ? Color.FromArgb(45, 45, 45) : Color.FromArgb(35, 35, 35);
        Color color = !Enabled ? SetupForm.Muted : Primary ? SetupForm.Surface : hovered ? SetupForm.Ink : ForeColor;
        if (!Link || hovered)
        {
            using (GraphicsPath shape = SetupForm.Rounded(new RectangleF(1, 1, Width - 2, Height - 2), Height / 4f))
            using (Brush fill = new SolidBrush(Enabled ? background : Color.FromArgb(35, 38, 43))) g.FillPath(fill, shape);
        }
        if (Focused && ShowFocusCues)
        {
            using (GraphicsPath focus = SetupForm.Rounded(new RectangleF(3, 3, Width - 6, Height - 6), Height / 4f))
            using (Pen outline = new Pen(Primary ? SetupForm.Surface : SetupForm.Ink, 1)) g.DrawPath(outline, focus);
        }
        using (Brush text = new SolidBrush(color))
        using (StringFormat format = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center })
            g.DrawString(Text, Font, text, new RectangleF(0, pressed ? 1 : 0, Width, Height), format);
    }
}

internal sealed class InstallJob : IDisposable
{
    private readonly SetupAssets assets;
    private readonly string target;
    private readonly bool desktop, reinstall;
    private readonly string temp = Path.Combine(Path.GetTempPath(), "AWLauncher-setup-" + Guid.NewGuid().ToString("N"));
    private readonly Native.InstallHandler handler;
    private long total, position, actionStep;
    private bool forward = true, actionData, preparing;
    private volatile bool cancel, canCancel = true;
    private double progress;
    private string status = "Подготавливаем файлы установщика";
    internal double Progress { get { return Interlocked.CompareExchange(ref progress, 0, 0); } }
    internal string Status { get { return Volatile.Read(ref status); } }
    internal bool CanCancel { get { return canCancel; } }
    internal bool Cancelling { get { return cancel; } }
    internal void Cancel() { if (canCancel) cancel = true; }
    internal void Preview() { Interlocked.Exchange(ref progress, 0.59); status = "Копируем файлы лаунчера"; }

    internal InstallJob(SetupAssets assets, string target, bool desktop, bool reinstall)
    { this.assets = assets; this.target = target; this.desktop = desktop; this.reinstall = reinstall; handler = OnMessage; }

    internal uint Run(bool administrative = false)
    {
        string msi = PreparePackage();
        if (msi == null || cancel) return 1602;
        string logDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AlpheusWorld", "InstallerLogs");
        Directory.CreateDirectory(logDirectory);
        Native.MsiEnableLog(0x3fff, Path.Combine(logDirectory, "AWLauncher-" + DateTime.Now.ToString("yyyyMMdd-HHmmss", CultureInfo.InvariantCulture) + ".log"), 0);
        uint oldUi = Native.MsiSetInternalUI(2, IntPtr.Zero);
        IntPtr previous;
        uint configured = Native.MsiSetExternalUIRecord(handler, 0x07ffffff, IntPtr.Zero, out previous);
        if (configured != 0) { Native.MsiSetInternalUI(oldUi, IntPtr.Zero); Native.MsiEnableLog(0, null, 0); throw new IOException("Не удалось подключиться к службе установки Windows"); }
        try
        {
            status = "Устанавливаем AWLauncher";
            string properties = administrative ? "ACTION=ADMIN TARGETDIR=\"" + target + "\" REBOOT=ReallySuppress" :
                "INSTALLDIR=\"" + target + "\" REBOOT=ReallySuppress JP_INSTALL_DESKTOP_SHORTCUT=" + (desktop ? "1" : "\"\"") + " JP_INSTALL_STARTMENU_SHORTCUT=1";
            if (reinstall && !administrative) properties += " REINSTALL=ALL REINSTALLMODE=vomus";
            uint result = Native.MsiInstallProduct(msi, properties);
            if (result == 0 || result == 3010) Interlocked.Exchange(ref progress, 1);
            return result;
        }
        finally
        {
            IntPtr ignored; Native.MsiSetExternalUIRecord(null, 0, IntPtr.Zero, out ignored);
            Native.MsiSetInternalUI(oldUi, IntPtr.Zero);
            Native.MsiEnableLog(0, null, 0);
            GC.KeepAlive(handler);
        }
    }

    private string PreparePackage()
    {
        string cacheDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AlpheusWorld", "InstallerCache", assets.PayloadHash);
        string cached = Path.Combine(cacheDirectory, "AWLauncher.msi");
        status = "Проверяем файлы установщика";
        if (File.Exists(cached) && Hash(cached) == assets.PayloadHash)
        { Interlocked.Exchange(ref progress, 0.08); return cached; }
        Directory.CreateDirectory(cacheDirectory);
        string staging = Path.Combine(cacheDirectory, Guid.NewGuid().ToString("N") + ".tmp");
        status = "Распаковываем установщик";
        try
        {
        using (Stream source = AwSetup.Resource("Payload"))
        {
            string cacheRoot = Path.GetPathRoot(cacheDirectory);
            long needed = source.Length + 32L * 1024 * 1024;
            if (string.Equals(cacheRoot, Path.GetPathRoot(target), StringComparison.OrdinalIgnoreCase)) needed += assets.AppBytes;
            if (new DriveInfo(cacheRoot).AvailableFreeSpace < needed) throw new IOException("На системном диске недостаточно места для установщика");
            using (SHA256 checksum = SHA256.Create())
            using (FileStream destination = new FileStream(staging, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            using (CryptoStream verified = new CryptoStream(destination, checksum, CryptoStreamMode.Write))
            {
                byte[] bytes = new byte[1024 * 1024]; int read;
                long copied = 0;
                while ((read = source.Read(bytes, 0, bytes.Length)) > 0)
                {
                    if (cancel) return null;
                    verified.Write(bytes, 0, read); copied += read;
                    Interlocked.Exchange(ref progress, source.Length > 0 ? 0.08 * copied / source.Length : 0);
                }
                verified.FlushFinalBlock();
                if (BitConverter.ToString(checksum.Hash).Replace("-", "").ToLowerInvariant() != assets.PayloadHash)
                    throw new IOException("Файл установщика повреждён — скачай его заново");
            }
        }
        if (cancel) return null;
        if (File.Exists(cached)) File.Delete(cached);
        File.Move(staging, cached);
        return cached;
        }
        finally { if (File.Exists(staging)) File.Delete(staging); }
    }

    private static string Hash(string path)
    {
        using (SHA256 hash = SHA256.Create())
        using (FileStream file = File.OpenRead(path)) return BitConverter.ToString(hash.ComputeHash(file)).Replace("-", "").ToLowerInvariant();
    }

    private int OnMessage(IntPtr context, uint type, uint record)
    {
        try
        {
            uint kind = type & 0xff000000;
            if (kind == 0x0a000000)
            {
                int operation = Native.MsiRecordGetInteger(record, 1);
                int ticks = Native.MsiRecordGetInteger(record, 2);
                if (operation == 0) { total = Math.Max(0, ticks); forward = Native.MsiRecordGetInteger(record, 3) == 0; position = forward ? 0 : total; actionData = false; preparing = Native.MsiRecordGetInteger(record, 4) == 1; }
                else if (operation == 1) { actionStep = Math.Max(0, ticks); actionData = Native.MsiRecordGetInteger(record, 3) != 0; }
                else if (operation == 2) position += forward ? ticks : -ticks;
                else if (operation == 3) total += Math.Max(0, ticks);
                UpdateProgress();
                return cancel && canCancel ? 2 : 1;
            }
            if (kind == 0x08000000)
            {
                actionData = false;
                string action = Native.RecordString(record, 1);
                if (action == "InstallFiles") status = "Копируем файлы лаунчера";
                else if (action == "CreateShortcuts") status = "Создаём ярлыки";
                else if (action == "WriteRegistryValues") status = "Сохраняем настройки установки";
                else if (action == "InstallFinalize") status = "Завершаем установку";
            }
            else if (kind == 0x09000000 && actionData) { position += forward ? actionStep : -actionStep; UpdateProgress(); }
            else if (kind == 0x0b000000 && Native.MsiRecordGetInteger(record, 1) == 2) canCancel = Native.MsiRecordGetInteger(record, 2) != 0;
            else if (kind == 0x05000000 || kind == 0x19000000) { status = "Закрой лаунчер и попробуй снова"; return 2; }
            else if (kind == 0x06000000) return 0;
            else if (kind == 0x01000000 || kind == 0x00000000 || kind == 0x07000000) return 2;
            return cancel && canCancel ? 2 : 1;
        }
        catch (Exception) { return -1; }
    }

    private void UpdateProgress()
    {
        if (total <= 0) return;
        double value = Math.Max(0, Math.Min(1, (double)position / total));
        if (!cancel) value = preparing ? 0.08 + value * 0.10 : 0.18 + value * 0.80;
        if (forward && !cancel) value = Math.Max(Progress, value);
        Interlocked.Exchange(ref progress, value);
    }

    internal static string ErrorMessage(uint result)
    {
        if (result == 1618) return "Другая установка ещё идёт — дождись её завершения";
        if (result == 1601) return "Служба установки Windows недоступна — попробуй перезагрузить компьютер";
        if (result == 1638) return "Уже установлена другая версия — сначала закрой лаунчер";
        return "Проверь доступ к папке и свободное место, затем попробуй снова";
    }

    public void Dispose()
    {
        string root = Path.GetFullPath(Path.GetTempPath()).TrimEnd('\\') + "\\";
        string owned = Path.GetFullPath(temp);
        if (!owned.StartsWith(root, StringComparison.OrdinalIgnoreCase) || !Path.GetFileName(owned).StartsWith("AWLauncher-setup-", StringComparison.Ordinal)) return;
        try { if (Directory.Exists(owned)) Directory.Delete(owned, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }
}

internal static class Native
{
    [UnmanagedFunctionPointer(CallingConvention.Winapi)] internal delegate int InstallHandler(IntPtr context, uint type, uint record);
    [DllImport("user32.dll")] internal static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] internal static extern bool ReleaseCapture();
    [DllImport("user32.dll")] internal static extern IntPtr SendMessage(IntPtr window, int message, IntPtr wParam, IntPtr lParam);
    [DllImport("dwmapi.dll")] internal static extern int DwmSetWindowAttribute(IntPtr window, int attribute, ref int value, int size);
    [DllImport("gdi32.dll")] internal static extern IntPtr AddFontMemResourceEx(IntPtr bytes, uint length, IntPtr reserved, out uint count);
    [DllImport("gdi32.dll")] internal static extern bool RemoveFontMemResourceEx(IntPtr font);
    [DllImport("msi.dll")] internal static extern uint MsiSetInternalUI(uint level, IntPtr owner);
    [DllImport("msi.dll")] internal static extern uint MsiSetExternalUIRecord(InstallHandler handler, uint filter, IntPtr context, out IntPtr previous);
    [DllImport("msi.dll", CharSet = CharSet.Unicode, EntryPoint = "MsiInstallProductW")] internal static extern uint MsiInstallProduct(string package, string properties);
    [DllImport("msi.dll", CharSet = CharSet.Unicode, EntryPoint = "MsiEnableLogW")] internal static extern uint MsiEnableLog(uint modes, string path, uint attributes);
    [DllImport("msi.dll")] internal static extern int MsiRecordGetInteger(uint record, uint field);
    [DllImport("msi.dll", CharSet = CharSet.Unicode, EntryPoint = "MsiRecordGetStringW")] private static extern uint MsiRecordGetString(uint record, uint field, StringBuilder value, ref uint size);
    [DllImport("msi.dll", CharSet = CharSet.Unicode, EntryPoint = "MsiEnumRelatedProductsW")] private static extern uint MsiEnumRelatedProducts(string upgrade, uint reserved, uint index, StringBuilder product);
    [DllImport("msi.dll", CharSet = CharSet.Unicode, EntryPoint = "MsiGetProductInfoW")] private static extern uint MsiGetProductInfo(string product, string property, StringBuilder value, ref uint length);
    [DllImport("msi.dll", CharSet = CharSet.Unicode, EntryPoint = "MsiQueryProductStateW")] private static extern int MsiQueryProductState(string product);
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)] private static extern int SHCreateItemFromParsingName(string path, IntPtr context, ref Guid iid, out IShellItem item);

    internal static string RecordString(uint record, uint field)
    {
        uint length = 256; StringBuilder text = new StringBuilder((int)length);
        if (MsiRecordGetString(record, field, text, ref length) == 234) { text = new StringBuilder((int)++length); MsiRecordGetString(record, field, text, ref length); }
        return text.ToString();
    }

    internal static string[] FindInstallation(string upgrade, string productCode)
    {
        string[] found = null;
        for (uint index = 0; ; index++)
        {
            StringBuilder product = new StringBuilder(39);
            if (MsiEnumRelatedProducts(upgrade, 0, index, product) != 0) break;
            if (MsiQueryProductState(product.ToString()) != 5) continue;
            uint length = 1024; StringBuilder location = new StringBuilder((int)length);
            if (MsiGetProductInfo(product.ToString(), "InstallLocation", location, ref length) == 0 && location.Length > 0 && File.Exists(Path.Combine(location.ToString(), "AWLauncher.exe")))
            {
                found = new[] { product.ToString(), location.ToString() };
                if (string.Equals(product.ToString(), productCode, StringComparison.OrdinalIgnoreCase)) return found;
            }
        }
        return found;
    }

    internal static string ChooseFolder(IntPtr owner, string initial)
    {
        IFileDialog dialog = (IFileDialog)Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7")));
        IShellItem start = null, result = null;
        try
        {
            dialog.SetOptions(0x20 | 0x40 | 0x800 | 0x10000000);
            dialog.SetTitle("Папка установки AWLauncher"); dialog.SetOkButtonLabel("Выбрать");
            Guid iid = typeof(IShellItem).GUID;
            if (Directory.Exists(initial) && SHCreateItemFromParsingName(initial, IntPtr.Zero, ref iid, out start) == 0) dialog.SetFolder(start);
            int code = dialog.Show(owner);
            if (code == unchecked((int)0x800704C7)) return null;
            Marshal.ThrowExceptionForHR(code);
            dialog.GetResult(out result);
            IntPtr name;
            result.GetDisplayName(0x80058000, out name);
            try { return Marshal.PtrToStringUni(name); } finally { Marshal.FreeCoTaskMem(name); }
        }
        finally
        {
            if (start != null) Marshal.FinalReleaseComObject(start);
            if (result != null) Marshal.FinalReleaseComObject(result);
            Marshal.FinalReleaseComObject(dialog);
        }
    }

    [ComImport, Guid("42F85136-DB7E-439C-85F1-E4075D135FC8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IFileDialog
    {
        [PreserveSig] int Show(IntPtr owner);
        void SetFileTypes(uint count, IntPtr filters); void SetFileTypeIndex(uint index); void GetFileTypeIndex(out uint index);
        void Advise(IntPtr events, out uint cookie); void Unadvise(uint cookie); void SetOptions(uint options); void GetOptions(out uint options);
        void SetDefaultFolder(IShellItem item); void SetFolder(IShellItem item); void GetFolder(out IShellItem item); void GetCurrentSelection(out IShellItem item);
        void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string name); void GetFileName(out IntPtr name);
        void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string title); void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string label);
        void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string label); void GetResult(out IShellItem item); void AddPlace(IShellItem item, uint location);
        void SetDefaultExtension([MarshalAs(UnmanagedType.LPWStr)] string extension); void Close(int result); void SetClientGuid(ref Guid guid); void ClearClientData(); void SetFilter(IntPtr filter);
    }
    [ComImport, Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItem
    {
        void BindToHandler(IntPtr context, ref Guid handler, ref Guid iid, out IntPtr result); void GetParent(out IShellItem parent);
        void GetDisplayName(uint kind, out IntPtr name); void GetAttributes(uint mask, out uint attributes); void Compare(IShellItem other, uint hint, out int order);
    }
}
