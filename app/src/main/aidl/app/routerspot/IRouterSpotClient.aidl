package app.routerspot;

// Приложение для служебного процесса: уведомления о состоянии.
oneway interface IRouterSpotClient {
    void onVpnStateChanged(boolean active);
}
