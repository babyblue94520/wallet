-- MySQL schema for the wallet demo.
-- DB: localhost mysql `test`

CREATE TABLE IF NOT EXISTS wallet (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  balance BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Simplified history table (action: OUT/IN) for WalletFacade flow.
CREATE TABLE IF NOT EXISTS wallet_history (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  created_at BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  action INT NOT NULL,
  before_balance BIGINT,
  after_balance BIGINT,
  PRIMARY KEY (id),
  KEY idx_wallet_history_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS deposit_record (
  `id` varchar(36) NOT NULL,
  `created_at` bigint NOT NULL DEFAULT '0',
  `channel_id` varchar(50) NOT NULL DEFAULT '',
  `channel_order_id` varchar(100) NOT NULL DEFAULT '',
  `status` int NOT NULL DEFAULT '0',
  `amount` bigint NOT NULL DEFAULT '0',
  `request_body` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `channel` (`channel_id`,`channel_order_id`),
  KEY `status` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS deposit_record2 (
  `id` varchar(36) NOT NULL,
  `created_at` bigint NOT NULL DEFAULT '0',
  `channel_id` varchar(50) NOT NULL DEFAULT '',
  `channel_order_id` varchar(100) NOT NULL DEFAULT '',
  `status` int NOT NULL DEFAULT '0',
  `amount` bigint NOT NULL DEFAULT '0',
  `request_body` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `channel` (`channel_id`,`channel_order_id`),
  KEY `status` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS deposit_record3(
    `id` bigint NOT NULL AUTO_INCREMENT,
    `created_at` bigint NOT NULL DEFAULT '0',
    `channel_id` varchar(50) NOT NULL DEFAULT '',
    `channel_order_id` varchar(100) NOT NULL DEFAULT '',
    `status` int NOT NULL DEFAULT '0',
    `amount` bigint NOT NULL DEFAULT '0',
    `request_body` text,
    PRIMARY KEY (`id`),
    UNIQUE KEY `channel`  (`channel_id`,`channel_order_id`),
    KEY `status` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS deposit_record4 (
     `id` bigint NOT NULL AUTO_INCREMENT,
     `created_at` bigint NOT NULL DEFAULT '0',
     `channel_id` varchar(50) NOT NULL DEFAULT '',
    `channel_order_id` varchar(100) NOT NULL DEFAULT '',
    `status` int NOT NULL DEFAULT '0',
    `amount` bigint NOT NULL DEFAULT '0',
    `request_body` text,
    PRIMARY KEY (`id`),
    KEY `channel` (`channel_id`,`channel_order_id`),
    KEY `status` (`status`,`created_at`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;