ALTER TABLE product MODIFY COLUMN user_id VARCHAR(48) NULL;
ALTER TABLE product MODIFY COLUMN player_id VARCHAR(48) NULL;
ALTER TABLE product ADD COLUMN seller_type VARCHAR(12) NOT NULL DEFAULT 'member';
ALTER TABLE product ADD CONSTRAINT ck_product_seller CHECK (
 (seller_type='club' AND user_id IS NULL AND player_id IS NULL) OR
 (seller_type='member' AND user_id IS NOT NULL AND player_id IS NOT NULL)
);

INSERT INTO product(id,user_id,player_id,seller_type,seller_name,game_id,service_type,title,description,price,task,active,created_at) VALUES
('club-hourly-delta',NULL,NULL,'club','诺曼部落','delta','hourly','诺曼部落 · 时长组队','俱乐部官方服务，付款后由通过对应游戏与类型考核的成员抢单。',6800,'',TRUE,0),
('club-escort-delta',NULL,NULL,'club','诺曼部落','delta','escort','诺曼部落 · 任务护航','俱乐部官方服务，付款后由通过对应游戏与类型考核的成员抢单。',12800,'100w纯带出',TRUE,0),
('club-hourly-valorant',NULL,NULL,'club','诺曼部落','valorant','hourly','诺曼部落 · 时长组队','俱乐部官方服务，付款后由通过对应游戏与类型考核的成员抢单。',6800,'',TRUE,0),
('club-escort-valorant',NULL,NULL,'club','诺曼部落','valorant','escort','诺曼部落 · 任务护航','俱乐部官方服务，付款后由通过对应游戏与类型考核的成员抢单。',12800,'100w纯带出',TRUE,0),
('club-hourly-lol',NULL,NULL,'club','诺曼部落','lol','hourly','诺曼部落 · 时长组队','俱乐部官方服务，付款后由通过对应游戏与类型考核的成员抢单。',6800,'',TRUE,0),
('club-escort-lol',NULL,NULL,'club','诺曼部落','lol','escort','诺曼部落 · 任务护航','俱乐部官方服务，付款后由通过对应游戏与类型考核的成员抢单。',12800,'100w纯带出',TRUE,0);
