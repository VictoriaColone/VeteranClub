CREATE INDEX ix_order_created ON club_order(created_at);
CREATE INDEX ix_order_paid ON club_order(paid_at);
CREATE INDEX ix_order_game_paid ON club_order(game_id,paid_at);
CREATE INDEX ix_income_player ON income_entry(player_id,created_at);
