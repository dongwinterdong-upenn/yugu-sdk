// 示例小程序入口。SDK 的客户端在页面里创建，页面卸载时释放。
App({
  onLaunch: function () {
    // 提前申请录音授权；用户拒绝时，录音器会以 PermissionException（90201）报告。
    wx.getSetting({
      success: function (res) {
        if (!res.authSetting || !res.authSetting['scope.record']) {
          wx.authorize({ scope: 'scope.record', fail: function () {} });
        }
      }
    });
  }
});
