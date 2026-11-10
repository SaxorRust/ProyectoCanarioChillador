# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['U:\\100056765\\saxprojects\\proyectoFinal_IOT\\desktop_app\\canario_app.py'],
    pathex=[],
    binaries=[],
    datas=[('U:\\100056765\\saxprojects\\proyectoFinal_IOT\\desktop_app\\canario.ico', '.'), ('U:\\100056765\\saxprojects\\proyectoFinal_IOT\\desktop_app\\tcl_data', '_tcl_data'), ('U:\\100056765\\saxprojects\\proyectoFinal_IOT\\desktop_app\\tk_data', '_tk_data')],
    hiddenimports=[],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='CanarioChillador-Desktop',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=['U:\\100056765\\saxprojects\\proyectoFinal_IOT\\desktop_app\\canario.ico'],
)
