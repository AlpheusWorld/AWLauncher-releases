using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows.Forms;

public static class AwUpdateSplash
{
    internal const string AppId = "AlpheusWorld.AWLauncher.Updater";
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)] private static extern int SetCurrentProcessExplicitAppUserModelID(string id);

    [STAThread]
    public static int Main(string[] args)
    {
        try
        {
            SetCurrentProcessExplicitAppUserModelID(AppId);
            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.ThrowException);
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(true);
            if (args.Length == 6 && args[0] == "--render")
            {
                float scale = float.Parse(args[4], CultureInfo.InvariantCulture);
                if (scale < .75f || scale > 3 || float.IsNaN(scale)) return 2;
                Render(args[1], args[2], args[3], args[5], scale, false);
                return 0;
            }
            if (args.Length != 8 || args[0] != "--run") return 2;
            return Run(int.Parse(args[1], CultureInfo.InvariantCulture), args[2], args[3], args[4], args[5], args[6], args[7]);
        }
        catch (Exception error)
        {
            if (args.Length == 8 && args[0] == "--run")
                try { File.WriteAllText(args[7] + ".error", error.ToString()); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            return 78;
        }
    }

    public static int Run(int launcher, string installer, string arguments, string app, string assets, string version, string ready)
    {
        Trace(ready,"starting native updater");
        string identity;
        using (SHA256 hash = SHA256.Create()) identity = BitConverter.ToString(hash.ComputeHash(Encoding.UTF8.GetBytes(Path.GetFullPath(app).ToUpperInvariant()))).Replace("-", "");
        Trace(ready,"identity prepared");
        using (Mutex owner = new Mutex(false, "Local\\AWLauncher.Update." + identity))
        {
            bool acquired;
            try { acquired = owner.WaitOne(0); } catch (AbandonedMutexException) { acquired = true; }
            if (!acquired) return 79;
            Trace(ready,"update lock acquired");
            try
            {
                Installation job = new Installation(launcher, installer, arguments);
                using (SplashLook look = new SplashLook(assets, version))
                using (SplashForm form = new SplashForm(look, job, app, ready)) { Trace(ready,"window constructed"); Application.Run(form); }
                return job.ExitCode;
            }
            finally { owner.ReleaseMutex(); }
        }
    }

    public static void Render(string assets, string version, string status, string output, float scale, bool failed)
    {
        using (SplashLook look = new SplashLook(assets, version))
        using (Bitmap bitmap = new Bitmap((int)(SplashLook.Width * scale), (int)(SplashLook.Height * scale)))
        using (Graphics graphics = Graphics.FromImage(bitmap))
        {
            look.Paint(graphics, bitmap.Width, bitmap.Height, scale, .45f, status, failed, false);
            bitmap.Save(output, ImageFormat.Png);
        }
    }

    internal static void Trace(string ready,string message) { try { File.AppendAllText(ready+".log",DateTime.Now.ToString("O")+" "+message+Environment.NewLine); } catch(IOException){} catch(UnauthorizedAccessException){} }

    internal static bool StartApp(string app)
    {
        try
        {
            ProcessStartInfo info = new ProcessStartInfo(app) { WorkingDirectory = Path.GetDirectoryName(app), UseShellExecute = true };
            Process.Start(info); return true;
        }
        catch (Exception) { return false; }
    }

    internal static bool AppWindowShown(string app)
    {
        foreach (Process process in Process.GetProcessesByName(Path.GetFileNameWithoutExtension(app)))
            using (process) { try { if (process.MainWindowHandle != IntPtr.Zero) return true; } catch (InvalidOperationException) { } }
        return false;
    }
}

internal sealed class Installation
{
    internal const int LauncherTimeout = -2;
    private readonly int launcher, launcherWaitMillis, retryMillis, busyWaitMillis;
    private readonly string installer, arguments;
    private volatile bool started, done;
    private volatile int exitCode = -1;
    private volatile string status = "Закрываем лаунчер";
    internal Installation(int launcher, string installer, string arguments, int launcherWaitMillis = 120000, int retryMillis = 3000, int busyWaitMillis = 300000)
    { this.launcher = launcher; this.installer = installer; this.arguments = arguments; this.launcherWaitMillis = launcherWaitMillis; this.retryMillis = retryMillis; this.busyWaitMillis = busyWaitMillis; }
    internal bool Started { get { return started; } }
    internal bool Done { get { return done; } }
    internal int ExitCode { get { return exitCode; } }
    internal string Status { get { return status; } }
    internal bool Succeeded { get { return exitCode == 0 || exitCode == 3010; } }
    internal string Failure
    {
        get
        {
            if (exitCode == LauncherTimeout) return "Лаунчер ещё не завершился. Закрой его и нажми «Повторить»";
            if (exitCode == 1618) return "Windows всё ещё устанавливает другую программу. Повтори обновление после её завершения";
            return exitCode < 0 ? "Не удалось запустить установку. Подробности сохранены в журнале" : "Windows не завершила установку (код " + exitCode + "). Можно повторить обновление";
        }
    }
    internal void Start()
    {
        if (started && !done) return;
        done = false; exitCode = -1; started = true; status = "Закрываем лаунчер";
        new Thread(Run) { IsBackground = true, Name = "AWLauncher-update" }.Start();
    }
    internal void Run()
    {
        started = true;
        try
        {
            Process previous = null;
            try { previous = Process.GetProcessById(launcher); } catch (ArgumentException) { }
            if (previous != null) using (previous)
            {
                if (!previous.WaitForExit(launcherWaitMillis)) { exitCode = LauncherTimeout; return; }
            }
            Stopwatch busy = Stopwatch.StartNew();
            while (true)
            {
                status = "Устанавливаем новую версию AWLauncher";
                ProcessStartInfo info = new ProcessStartInfo(installer, arguments) { UseShellExecute = false, CreateNoWindow = true };
                using (Process process = Process.Start(info)) { process.WaitForExit(); exitCode = process.ExitCode; }
                if (exitCode != 1618 || busy.ElapsedMilliseconds >= busyWaitMillis) break;
                status = "Windows завершает другую установку. Обновление продолжится автоматически";
                Thread.Sleep((int)Math.Min(retryMillis, Math.Max(1, busyWaitMillis - busy.ElapsedMilliseconds)));
            }
        }
        catch (Exception error)
        {
            exitCode = -1;
            try { File.AppendAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "helper.log"), DateTime.Now.ToString("O") + " " + error + Environment.NewLine); } catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
        finally { done = true; }
    }
}

internal sealed class SplashLook : IDisposable
{
    internal const int Width = 560, Height = 280;
    internal static readonly Color Background = Color.FromArgb(0x12, 0x14, 0x16);
    internal static readonly Color Ink = Color.FromArgb(0xF0, 0xF2, 0xF3);
    internal static readonly Color Muted = Color.FromArgb(0x99, 0xA1, 0xA6);
    internal static readonly Color Accent = Color.FromArgb(0x54, 0xD9, 0xA1);
    private readonly Image wordmark;
    private readonly List<PrivateFontCollection> collections = new List<PrivateFontCollection>();
    private readonly FontFamily regular, bold;
    private readonly string version;
    internal SplashLook(string assets, string version)
    {
        wordmark = Image.FromFile(Path.Combine(assets, "wordmark.png"));
        regular = Family(Path.Combine(assets, "Onest-Regular.ttf")); bold = Family(Path.Combine(assets, "Onest-Bold.ttf")); this.version = "v" + version;
    }
    internal Font Font(float pixels, bool strong) { var family = strong ? bold : regular; return new Font(family, pixels, family.IsStyleAvailable(FontStyle.Regular) ? FontStyle.Regular : FontStyle.Bold, GraphicsUnit.Pixel); }
    private FontFamily Family(string file)
    {
        PrivateFontCollection collection = new PrivateFontCollection(); collection.AddFontFile(file); collections.Add(collection); return collection.Families[0];
    }
    internal static GraphicsPath Rounded(RectangleF rect, float radius)
    {
        GraphicsPath path = new GraphicsPath(); float d = radius * 2;
        path.AddArc(rect.X, rect.Y, d, d, 180, 90); path.AddArc(rect.Right-d, rect.Y, d, d, 270, 90);
        path.AddArc(rect.Right-d, rect.Bottom-d, d, d, 0, 90); path.AddArc(rect.X, rect.Bottom-d, d, d, 90, 90);path.CloseFigure();return path;
    }
    internal void Paint(Graphics g, int width, int height, float scale, float phase, string status, bool failed, bool launching)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias; g.InterpolationMode = InterpolationMode.HighQualityBicubic;g.PixelOffsetMode = PixelOffsetMode.HighQuality;g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;g.Clear(Background);
        g.DrawImage(wordmark, 24*scale, 20*scale, 176*scale, 176*scale*wordmark.Height/wordmark.Width);
        using (Font font = Font(12*scale,false)) using (Brush brush = new SolidBrush(Muted)) g.DrawString(version,font,brush,new RectangleF(width-160*scale,25*scale,90*scale,22*scale),new StringFormat { Alignment=StringAlignment.Far });
        using (Pen line = new Pen(Color.FromArgb(0x2D,0x32,0x36))) g.DrawLine(line,0,64*scale,width,64*scale);
        using (Font title = Font(23*scale,true)) using (Brush brush = new SolidBrush(Ink)) g.DrawString(failed?"Не удалось обновить лаунчер":launching?"Открываем AWLauncher":"Устанавливаем обновление",title,brush,24*scale,83*scale,StringFormat.GenericTypographic);
        using (Font body = Font(14*scale,false)) using (Brush brush = new SolidBrush(failed?Color.FromArgb(0xEB,0xAA,0xAF):Muted))
            g.DrawString(status,body,brush,new RectangleF(24*scale,126*scale,width-48*scale,52*scale),StringFormat.GenericTypographic);
        RectangleF track=new RectangleF(24*scale,192*scale,width-48*scale,5*scale);
        using(GraphicsPath path=Rounded(track,2.5f*scale)) using(Brush brush=new SolidBrush(Color.FromArgb(0x2B,0x31,0x35)))
        {
            g.FillPath(brush,path);
            if(!failed){var saved=g.Save();g.SetClip(path);using(Brush fill=new SolidBrush(Accent))g.FillRectangle(fill,track.X+track.Width*(-.3f+1.3f*phase),track.Y,track.Width*.3f,track.Height);g.Restore(saved);}
        }
        if(!failed)using(Font small=Font(12*scale,false))using(Brush brush=new SolidBrush(Muted))g.DrawString("Окно можно свернуть — обновление продолжится",small,brush,24*scale,228*scale,StringFormat.GenericTypographic);
    }
    public void Dispose(){wordmark.Dispose();foreach(var fonts in collections)fonts.Dispose();}
}

internal sealed class SplashForm : Form
{
    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr window,int attribute,ref int value,int size);
    [DllImport("user32.dll")] private static extern uint GetDpiForWindow(IntPtr window);
    [StructLayout(LayoutKind.Sequential)] private struct NativeRect { public int Left,Top,Right,Bottom; }
    private readonly SplashLook look;
    private readonly Installation job;
    private readonly string app,ready;
    private readonly System.Windows.Forms.Timer timer=new System.Windows.Forms.Timer();
    private readonly Stopwatch clock=Stopwatch.StartNew();
    internal readonly Button MinimizeButton,RetryButton,ReturnButton;
    private readonly Dictionary<int,Font> fonts=new Dictionary<int,Font>();
    private float scale=1;
    private long launchedAt=-1;
    private bool finishing,failed;
    internal bool Launched {get;private set;}
    internal SplashForm(SplashLook look,Installation job,string app,string ready)
    {
        this.look=look;this.job=job;this.app=app;this.ready=ready;
        AutoScaleMode=AutoScaleMode.None;FormBorderStyle=FormBorderStyle.None;StartPosition=FormStartPosition.Manual;
        ShowInTaskbar=true;TopMost=false;Text="Обновление AWLauncher";BackColor=SplashLook.Background;DoubleBuffered=true;KeyPreview=true;MaximizeBox=false;MinimizeBox=true;
        Icon=new Icon(Path.Combine(Path.GetDirectoryName(ready),"app.ico"));
        MinimizeButton=Button("−","Свернуть обновление",false);MinimizeButton.Click+=delegate{WindowState=FormWindowState.Minimized;};
        RetryButton=Button("Повторить","Повторить обновление",true);RetryButton.Click+=delegate{failed=false;RetryButton.Visible=ReturnButton.Visible=false;job.Start();Invalidate();};
        ReturnButton=Button("Открыть лаунчер","Вернуться в лаунчер",false);ReturnButton.Click+=delegate{Launched=AwUpdateSplash.StartApp(app);Finish();};
        RetryButton.Visible=ReturnButton.Visible=false;AcceptButton=RetryButton;SetScale(1);
        timer.Interval=25;timer.Tick+=delegate{Advance();};
    }
    protected override CreateParams CreateParams {get{var p=base.CreateParams;p.Style|=0x00020000|0x00080000;return p;}}
    private Button Button(string text,string accessible,bool primary)
    {
        Button b=new Button{Text=text,AccessibleName=accessible,FlatStyle=FlatStyle.Flat,TabStop=true,BackColor=primary?SplashLook.Accent:Color.FromArgb(0x27,0x2C,0x30),ForeColor=primary?Color.FromArgb(0x10,0x33,0x27):SplashLook.Ink,UseVisualStyleBackColor=false};
        b.Text="";b.Tag=text;b.FlatAppearance.BorderSize=0;
        b.Paint+=delegate(object sender,PaintEventArgs e){
            Graphics g=e.Graphics;g.SmoothingMode=SmoothingMode.AntiAlias;g.TextRenderingHint=TextRenderingHint.AntiAliasGridFit;g.Clear(BackColor);
            bool hover=b.ClientRectangle.Contains(b.PointToClient(Cursor.Position));
            Color fill=primary?(hover?Color.FromArgb(0x73,0xE4,0xB5):SplashLook.Accent):(hover?Color.FromArgb(0x34,0x3D,0x43):Color.FromArgb(0x27,0x2C,0x30));
            using(GraphicsPath path=SplashLook.Rounded(new RectangleF(0,0,b.Width,b.Height),6*scale))using(Brush brush=new SolidBrush(fill)){g.FillPath(brush,path);if(b.Focused)using(Pen border=new Pen(SplashLook.Muted))g.DrawPath(border,path);}
            if(text=="−")using(Pen pen=new Pen(SplashLook.Ink,Math.Max(1,scale))){g.DrawLine(pen,b.Width/2f-5*scale,b.Height/2f,b.Width/2f+5*scale,b.Height/2f);}
            else using(Brush brush=new SolidBrush(b.ForeColor))using(StringFormat format=new StringFormat{Alignment=StringAlignment.Center,LineAlignment=StringAlignment.Center})g.DrawString(text,b.Font,brush,b.ClientRectangle,format);
        };
        Controls.Add(b);return b;
    }
    internal void SetScale(float value)
    {
        scale=Math.Max(.75f,value);ClientSize=new Size(P(SplashLook.Width),P(SplashLook.Height));
        MinimizeButton.Bounds=new Rectangle(ClientSize.Width-P(56),P(14),P(36),P(34));
        RetryButton.Bounds=new Rectangle(P(24),P(220),P(192),P(40));ReturnButton.Bounds=new Rectangle(P(228),P(220),P(308),P(40));
        foreach(Button b in new[]{MinimizeButton,RetryButton,ReturnButton}){int pixels=P(b==MinimizeButton?20:14),key=pixels*2+(b==MinimizeButton?0:1);Font font;if(!fonts.TryGetValue(key,out font)){font=look.Font(pixels,b!=MinimizeButton);fonts.Add(key,font);}b.Font=font;}
        Invalidate();
    }
    private int P(float v){return (int)Math.Round(v*scale);}
    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);AwUpdateSplash.Trace(ready,"window handle created");try{SetScale(GetDpiForWindow(Handle)/96f);}catch(EntryPointNotFoundException){using(Graphics g=CreateGraphics())SetScale(g.DpiX/96f);}
        Rectangle area=Screen.FromPoint(Cursor.Position).WorkingArea;Location=new Point(area.Left+(area.Width-Width)/2,area.Top+(area.Height-Height)/2);
        try{int corner=2;DwmSetWindowAttribute(Handle,33,ref corner,4);int outline=0x0036322D;DwmSetWindowAttribute(Handle,34,ref outline,4);}catch(DllNotFoundException){}
    }
    protected override void WndProc(ref Message m)
    {
        if(m.Msg==0x02E0){var bounds=(NativeRect)Marshal.PtrToStructure(m.LParam,typeof(NativeRect));SetScale((m.WParam.ToInt64()&0xffff)/96f);Location=new Point(bounds.Left,bounds.Top);m.Result=IntPtr.Zero;return;}
        base.WndProc(ref m);
        if(m.Msg==0x0084&&m.Result.ToInt32()==1){long point=m.LParam.ToInt64();Point client=PointToClient(new Point((short)(point&0xffff),(short)((point>>16)&0xffff)));if(client.Y<P(64)&&!MinimizeButton.Bounds.Contains(client))m.Result=new IntPtr(2);}
    }
    protected override void OnShown(EventArgs e){base.OnShown(e);AwUpdateSplash.Trace(ready,"window shown");File.WriteAllText(ready,"");job.Start();timer.Start();}
    protected override void OnFormClosing(FormClosingEventArgs e){if(!finishing&&e.CloseReason==CloseReason.UserClosing){e.Cancel=true;WindowState=FormWindowState.Minimized;}base.OnFormClosing(e);}
    protected override void OnKeyDown(KeyEventArgs e){if(e.KeyCode==Keys.Escape){WindowState=FormWindowState.Minimized;e.Handled=true;}base.OnKeyDown(e);}
    protected override void OnPaint(PaintEventArgs e){look.Paint(e.Graphics,ClientSize.Width,ClientSize.Height,scale,(clock.ElapsedMilliseconds%1500)/1500f,failed?job.Failure:Launched?"Запускаем новую версию":job.Status,failed,Launched);}
    private void Advance()
    {
        Invalidate();if(!job.Done)return;
        if(!job.Succeeded){if(!failed){failed=true;RetryButton.Visible=ReturnButton.Visible=true;}return;}
        if(launchedAt<0){Launched=AwUpdateSplash.StartApp(app);launchedAt=clock.ElapsedMilliseconds;if(!Launched)Finish();return;}
        if(clock.ElapsedMilliseconds-launchedAt>15000||AwUpdateSplash.AppWindowShown(app))Finish();
    }
    internal void Finish(){finishing=true;timer.Stop();Close();}
    protected override void Dispose(bool disposing){Icon owned=disposing?Icon:null;base.Dispose(disposing);if(disposing){timer.Dispose();if(owned!=null)owned.Dispose();foreach(Font font in fonts.Values)font.Dispose();fonts.Clear();}}
}
