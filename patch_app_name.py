path = "app/src/main/AndroidManifest.xml"
src = open(path, encoding="utf-8").read()

old = '''    <application
        android:label="PichaPlus"'''
new = '''    <application
        android:label="MyPichaPlus"'''

if old not in src:
    print("❌ Pattern not found.")
else:
    src = src.replace(old, new)
    open(path, "w", encoding="utf-8").write(src)
    print("✅ App label changed to MyPichaPlus.")
