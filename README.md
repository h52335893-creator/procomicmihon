# ProComic extension — final verification v2

## Root cause fixed

The series page exposes only the newest 30 chapters in rendered HTML. The complete chapter list is available through `GET /api/public/chapters?contentId=...`, with a maximum page size of 50 and a `hasMore` flag. The previous extension parsed only visible `<a>` links, which caused older chapters to disappear.

The updated parser uses `contentId`, follows all pagination pages up to a safe limit, filters approved Arabic chapters, builds chapter URLs from the series slug, chapter number, and chapter ID, and preserves non-numeric chapter labels rather than dropping them.

## Verification results

| Check | Result |
|---|---|
| Kotlin `compileReleaseKotlin` | **BUILD SUCCESSFUL** |
| Android `assembleRelease` | **BUILD SUCCESSFUL** |
| Generated APK | `tachiyomi-ar.procomic-v1.6.3.apk` |
| Generated JAR | `tachiyomi-ar.procomic-v1.6.3.jar` |
| Real Man chapter API | 268 unique approved Arabic chapters, numbers 1–269 |
| API pagination | Pages 1–6 returned all Real Man records; page 7 empty |
| 20-series API sample | HTTP 200 for every tested series |
| Popular and Latest API | HTTP 200 with results using the public search path |
| Real Man chapter pages | HTTP 200 and image URLs found across sample chapters |
| Image CDN samples | HTTP 200, AVIF content |
| Mihon index JSON | Valid schema fields and absolute APK/icon URLs |

## Expected limitations

The extension does not bypass login, safe-browsing restrictions, coin locks, shortlink locks, or exclusive chapters. If the website itself hides a chapter, Mihon cannot read it without the user having the required access on the website.

The final source and workflow are in `procomic-final-bundle-verified-v2.zip`. Upload the contents to the root of the `main` branch, replacing the old `.github` and `procomic` files, then let GitHub Actions publish the new APK and index to the `repo` branch.
