-- 日历订阅：每个用户一个高熵只读订阅 Token，无需登录即可只读订阅 .ics，可随时重置。

ALTER TABLE `user`
  ADD COLUMN subscribe_token VARCHAR(64) NULL;

CREATE UNIQUE INDEX uk_user_subscribe_token
  ON `user`(subscribe_token);