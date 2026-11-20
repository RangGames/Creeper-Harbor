-- (추가 제안) 경매 내 거래 기록: 산 것 조회 (판 것은 idx_auction_sale_pair (seller, …)를 쓴다)
CREATE INDEX idx_auction_sale_buyer ON auction_sale (buyer, sold_at);
