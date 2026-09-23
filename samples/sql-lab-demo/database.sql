SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
CREATE TABLE `apks` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL,
  `downloads` int(11) NOT NULL DEFAULT 0,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO `apks` (`id`,`name`,`downloads`) VALUES
(1,'LocalDev',100),(2,'Demo APK',25),(3,'SQL Lab',8);
