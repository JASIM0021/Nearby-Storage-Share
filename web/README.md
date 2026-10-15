# Nearby Storage Share - website and legal pages

Plain static HTML/CSS. No build step, no scripts, no external requests (so the pages work offline and do not track visitors).

| File | Purpose |
|---|---|
| `index.html` | Landing page with links to everything below |
| `privacy-policy.html` | Privacy Policy (use this URL in the Google Play Console "Privacy policy" field) |
| `terms-and-conditions.html` | Terms and Conditions |
| `open-source-licenses.html` | Third-party open-source notices (AndroidX, Kotlin, ...) |
| `license.html`, `LICENSE.txt` | The app's own source-available license (generated from `../LICENSE`) |
| `licenses/APACHE-2.0.txt` | Full Apache-2.0 text required by the third-party libraries |
| `css/style.css`, `assets/` | Shared styling and icons |

## Details used in these documents

- Publisher: Sk Jasimudin (Todayintech Solution)
- Contact / privacy grievance email: contact@todayintech.in (replies promised within 30 days)
- Governing law: India (Indian Contract Act 1872, Information Technology Act 2000; consumer rights under the Consumer Protection Act 2019 are preserved; privacy rights refer to the Digital Personal Data Protection Act 2023)
- Minimum age: 18 (a parent or legal guardian may accept on behalf of a minor)

If any of these change, update them in every page, in `../LICENSE`, then copy `../LICENSE` to `LICENSE.txt` and refresh the text block in `license.html`.

## Keeping the documents true

These pages describe what the app does in Android version 1.5.0 and Windows app version 1.0.0. Update the Privacy Policy (and its "Last updated" date) whenever the app's
data handling changes - for example if you add analytics, accounts, a server, or new permissions - and update the version and library
list in `open-source-licenses.html` when dependencies change (`gradle/libs.versions.toml`).

## Hosting

Any static host works (GitHub Pages, Netlify, Cloudflare Pages, your own server): upload this folder as-is. To preview locally:

```
npx serve web
```

These documents are a good-faith description of the app, not legal advice. Have a lawyer review them before you rely on them, especially the Terms, the License and the privacy policy's statements about Indian law.
