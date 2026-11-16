import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "TMOHentai"
    versionCode = 12
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "es"
        baseUrl = "https://tmohentai.app"
    }

    deeplink {
        host("tmohentai.app")
        host("www.tmohentai.app")
        host("tmohentai.com")
        path("/contents/..*")
        path("/library/..*")
    }
}
