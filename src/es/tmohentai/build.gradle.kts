import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "TMOHentai"
    versionCode = 11
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        lang = "es"
        baseUrl = "https://tmohentai.app"
    }

    deeplink {
        host("tmohentai.app")
        host("tmohentai.com")
        path("/contents/.*")
        path("/library/manga/.*")
    }
}
