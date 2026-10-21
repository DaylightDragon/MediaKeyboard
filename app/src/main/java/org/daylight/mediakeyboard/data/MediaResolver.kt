package org.daylight.mediakeyboard.data

interface MediaResolver {
    /** Идентификатор сервиса: "klipy", "giphy", ... */
    val serviceId: String

    /** Отображаемое имя для UI ("Klipy", "Giphy") */
    val displayName: String

    /** Проверяет, относится ли URL к этому сервису. */
    fun detect(url: String): Boolean

    /** Извлекает ServiceRef из URL. null — если URL не подходит. */
    fun extractRef(url: String): ServiceRef?

    /**
     * Разрешает ссылку через API.
     * @param ref данные, извлечённые из URL
     * @param apiKey ключ пользователя (может быть null — тогда ошибка)
     * @throws MissingApiKeyException если ключ не задан
     * @throws ApiException если сервис вернул ошибку
     */
    suspend fun resolve(ref: ServiceRef, apiKey: String?): ResolvedMedia
}
