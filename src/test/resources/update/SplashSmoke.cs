using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

internal static class SplashSmoke
{
    [DllImport("user32.dll")] private static extern IntPtr SendMessage(IntPtr hwnd,int message,IntPtr wp,IntPtr lp);
    [DllImport("user32.dll")] private static extern bool IsIconic(IntPtr hwnd);
    [DllImport("shell32.dll")] private static extern int GetCurrentProcessExplicitAppUserModelID(out IntPtr id);
    private static void Assert(bool value,string message){if(!value)throw new Exception(message);}
    private static string Quote(string value){return ((char)34)+value+((char)34);}
    private static void Wait(Installation job){var clock=Stopwatch.StartNew();while(!job.Done&&clock.ElapsedMilliseconds<10000)Thread.Sleep(10);Assert(job.Done,"Installation test timed out");}
    private static void Pump(Func<bool> ready){var clock=Stopwatch.StartNew();while(!ready()&&clock.ElapsedMilliseconds<30000){Application.DoEvents();Thread.Sleep(10);}Assert(ready(),"Window test timed out");}
    [STAThread]
    public static int Main(string[] args)
    {
        if(args.Length==3&&args[0]=="--fake")
        {
            int count=File.Exists(args[1])?int.Parse(File.ReadAllText(args[1])):0;File.WriteAllText(args[1],(++count).ToString());
            if(args[2]=="busy")return 1618;
            if(args[2]=="error-once")return count==1?1603:0;
            return count<3?1618:0;
        }
        try
        {
            string assets=args[0],self=Assembly.GetExecutingAssembly().Location;
            Assert(AwUpdateSplash.Main(new[]{"--render",assets,"1.0.26","Устанавливаем новую версию AWLauncher","1",Path.Combine(assets,"render-100.png")})==0,"Native entry point failed");
            IntPtr id;Assert(GetCurrentProcessExplicitAppUserModelID(out id)==0,"Updater has no app identity");try{Assert(Marshal.PtrToStringUni(id)==AwUpdateSplash.AppId,"Wrong taskbar identity");}finally{Marshal.FreeCoTaskMem(id);}
            foreach(float scale in new[]{1.5f,2f})AwUpdateSplash.Render(assets,"1.0.26","Windows завершает другую установку. Обновление продолжится автоматически",Path.Combine(assets,"render-"+(int)(scale*100)+".png"),scale,false);
            string helper=Path.Combine(assets,"AWLauncher.Update.exe"),helperReady=Path.Combine(assets,"native-ready"),duplicateReady=Path.Combine(assets,"duplicate-ready");File.Delete(helperReady);File.Delete(duplicateReady);
            string command="--run "+Process.GetCurrentProcess().Id+" "+Quote(self)+" "+Quote("")+" "+Quote(Path.Combine(assets,"not-an-application.exe"))+" "+Quote(assets)+" 1.0.26 ";
            using(var native=Process.Start(new ProcessStartInfo(helper,command+Quote(helperReady)){UseShellExecute=false,CreateNoWindow=true}))
            {
                try
                {
                    Pump(()=>File.Exists(helperReady)||native.HasExited);if(!File.Exists(helperReady))throw new Exception("Native updater exited before ready: "+native.ExitCode+" "+(File.Exists(helperReady+".error")?File.ReadAllText(helperReady+".error"):"no error file"));native.Refresh();Assert(native.ProcessName=="AWLauncher.Update"&&native.MainWindowHandle!=IntPtr.Zero,"Update window still belongs to a shell host");
                    using(var duplicate=Process.Start(new ProcessStartInfo(helper,command+Quote(duplicateReady)){UseShellExecute=false,CreateNoWindow=true}))
                    {Assert(duplicate.WaitForExit(5000)&&duplicate.ExitCode==79&&!File.Exists(duplicateReady),"Duplicate update was not rejected");}
                }
                finally{if(!native.HasExited){native.Kill();native.WaitForExit(5000);}}
            }
            using(var look=new SplashLook(assets,"1.0.26"))
            using(var form=new SplashForm(look,new Installation(Process.GetCurrentProcess().Id,self,"",20000),Path.Combine(assets,"not-an-application.exe"),Path.Combine(assets,"ready")))
            {
                form.Show();Application.DoEvents();Assert(form.ShowInTaskbar&&!form.TopMost&&form.Icon!=null,"Window identity/icon is missing");
                Point point=form.PointToScreen(new Point(32,28));long packed=(long)(uint)(point.X&0xffff)|((long)(point.Y&0xffff)<<16);
                Assert(SendMessage(form.Handle,0x84,IntPtr.Zero,new IntPtr(packed)).ToInt32()==2,"Header does not support native dragging");
                form.MinimizeButton.PerformClick();Application.DoEvents();Assert(IsIconic(form.Handle),"Minimize did not reach the OS");
                form.WindowState=FormWindowState.Normal;Application.DoEvents();form.SetScale(1.5f);
                Assert(form.ClientSize==new Size(840,420),"DPI layout is incorrect");
                using(var image=new Bitmap(form.ClientSize.Width,form.ClientSize.Height)){form.DrawToBitmap(image,form.ClientRectangle);image.Save(Path.Combine(assets,"window-150.png"),ImageFormat.Png);}
                form.Finish();
            }
            Console.WriteLine("Window minimize, drag and DPI passed");
            string counter=Path.Combine(assets,"retry-count");File.Delete(counter);
            var retry=new Installation(int.MaxValue,self,"--fake "+Quote(counter)+" retry",50,10,5000);retry.Start();Wait(retry);
            Assert(retry.Succeeded&&File.ReadAllText(counter)=="3","1618 was not retried until success");
            Console.WriteLine("Busy installer retry passed");
            string blockedCounter=Path.Combine(assets,"blocked-count");File.Delete(blockedCounter);
            var blocked=new Installation(int.MaxValue,self,"--fake "+Quote(blockedCounter)+" busy",50,10,50);blocked.Start();Wait(blocked);
            Assert(blocked.ExitCode==1618&&!blocked.Succeeded,"Busy installer did not remain recoverable");
            string parentCounter=Path.Combine(assets,"parent-count");File.Delete(parentCounter);
            var parent=new Installation(Process.GetCurrentProcess().Id,self,"--fake "+Quote(parentCounter)+" retry",20,10,50);parent.Start();Wait(parent);
            Assert(parent.ExitCode==Installation.LauncherTimeout&&parent.ExitCode!=1618&&!File.Exists(parentCounter),"Parent timeout was reported as an MSI error or started an installer");
            string failedCounter=Path.Combine(assets,"failure-count");File.Delete(failedCounter);
            var failed=new Installation(int.MaxValue,self,"--fake "+Quote(failedCounter)+" error-once",50,10,5000);
            using(var look=new SplashLook(assets,"1.0.26"))
            using(var form=new SplashForm(look,failed,Path.Combine(assets,"not-an-application.exe"),Path.Combine(assets,"failed-ready")))
            {
                form.Show();Pump(()=>failed.Done&&form.RetryButton.Visible);Assert(failed.ExitCode==1603&&form.ReturnButton.Visible,"Failure actions are missing");
                form.SetScale(1.5f);using(var image=new Bitmap(form.ClientSize.Width,form.ClientSize.Height)){form.DrawToBitmap(image,form.ClientRectangle);image.Save(Path.Combine(assets,"failure-150.png"),ImageFormat.Png);}
                form.RetryButton.PerformClick();Pump(()=>failed.Done&&failed.Succeeded);Assert(File.ReadAllText(failedCounter)=="2","Manual retry did not run the installer again");form.Finish();
            }
            Console.WriteLine("Native identity, icon, drag hit testing, OS minimize, 100/150/200% DPI, automatic 1618 retry, parent timeout and manual recovery passed");return 0;
        }
        catch(Exception error){Console.Error.WriteLine(error);return 1;}
    }
}
