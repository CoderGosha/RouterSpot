package app.routerspot;

// Процесс :guard для приложения: следит за target и при его гибели выключает раздачу.
interface IUsbGuard {
    void watch(IBinder target);
    void unwatch();
}
