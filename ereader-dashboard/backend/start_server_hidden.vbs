' Starts the dashboard server without a console window; output goes to backend\logs\server.log.
Set fso = CreateObject("Scripting.FileSystemObject")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
CreateObject("WScript.Shell").Run "cmd /c cd /d """ & dir & """ && py -3.10 -u -W ignore dashboard_server.py serve >> logs\server.log 2>&1", 0, False
