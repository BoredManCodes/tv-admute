' Runs a .ps1 with no console window. Usage: wscript.exe //nologo run-hidden.vbs "<script.ps1>"
Set sh = CreateObject("WScript.Shell")
sh.Run "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File """ & WScript.Arguments(0) & """", 0, False
