import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ProComic"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"
    source {
        name = "بروكوميك"
        lang = "ar"
        baseUrl = "https://procomic.net"
    }
}
