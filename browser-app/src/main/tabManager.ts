/**
 * TODO (PHASE 7): Quan ly tab bang WebContentsView (Electron 30+ API moi,
 * KHONG dung <webview> tag da deprecated).
 *
 * Trach nhiem du kien:
 *   - createTab(url): tao WebContentsView moi, gan vao BrowserWindow, resize
 *     khop vung noi dung (duoi TabBar/AddressBar).
 *   - closeTab(id), switchTab(id): an/hien view tuong ung.
 *   - navigate(id, url), goBack(id), goForward(id), reload(id).
 *   - Chan popup khong mong muon, mo link target=_blank thanh tab moi
 *     (qua setWindowOpenHandler).
 */
export {}
