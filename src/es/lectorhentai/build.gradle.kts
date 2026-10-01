import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Lector Hentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        baseUrl = "https://lectorhentai.com"
        lang = "es"
    }

    deeplink {
        host("lectorhentai.com")
        path("/manga/.*")
        path("/read/.*")
    }
}
