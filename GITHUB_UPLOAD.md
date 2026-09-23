# Uploading LocalDev to GitHub

Recommended repository contents:
- `app/`
- `gradle/`
- `branding/`
- `samples/`
- `tools/`
- root Gradle files and documentation

Do not upload:
- signing `.jks` / `.keystore`
- `local.properties`
- `.idea/`
- `.gradle/`
- `build/` or `app/build/`
- production credentials, API keys, passwords, or tokens

## Suggested first commit

```bash
git init
git add .
git commit -m "Initial LocalDev 1.1.2 source"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/LocalDev.git
git push -u origin main
```

## License

This package does not choose a software license on your behalf.
If you want other people to legally reuse/modify/distribute the code, add a LICENSE file
such as MIT, Apache-2.0, GPL, or another license that matches your intent.
