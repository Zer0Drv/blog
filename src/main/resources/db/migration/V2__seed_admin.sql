-- 种子：博主账号 admin / admin123（BCrypt 哈希与 admin 项目种子一致）
INSERT INTO `user` (`username`, `password`, `email`, `nickname`, `role`, `status`)
SELECT 'admin', '$2a$10$ubGNSIh9Rg5Nl49HPoOD6ecRzSTnf6TgNBBPaN2uEpPS2eoPB2hXS', 'admin@blog.local', '博主', 'ADMIN', 0
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE `username` = 'admin');
