package app.routerspot;

import app.routerspot.IRouterSpotClient;

// Служебный процесс (uid shell) для приложения. Ошибки возвращаются строкой, null — успех.
interface IRouterSpotServer {
    String start(String vlessLink);
    void stop();
    // type — TetheringManager.TETHERING_*: Wi-Fi или USB.
    String setTethering(int type, boolean on);
    String status();
    // JSON {проверка: {ok, detail}}; с профилем — ещё и сквозная проверка туннеля.
    String diagnose(String vlessLink);
    // Отладка: скачать url через туннель, ответ «байты мс».
    String speedtest(String url);
    // Приложение: служебный процесс шлёт ему состояние и следит за его гибелью.
    void attachClient(IRouterSpotClient client);
    // Служба shell: запускает USB-службу в процессе приложения и делегирует ей свои права.
    String startUsbHost(int appUid);
    // USB-служба: всё остановить и отдать права обратно.
    void quitUsbHost();
}
