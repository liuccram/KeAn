-- 山东普通本科高校及常见校区（保留已有「演示大学」）
-- 来源：教育部高校名单口径（大学生必备网 2026 整理）+ 各校公开校区信息
ALTER TABLE school
    ADD UNIQUE INDEX uk_school_name (name);

ALTER TABLE campus
    ADD UNIQUE INDEX uk_campus_school_name (school_id, name);

INSERT IGNORE INTO school (name, status) VALUES
('山东大学', 1),
('中国海洋大学', 1),
('山东科技大学', 1),
('中国石油大学（华东）', 1),
('青岛科技大学', 1),
('济南大学', 1),
('青岛理工大学', 1),
('山东建筑大学', 1),
('齐鲁工业大学', 1),
('山东理工大学', 1),
('山东农业大学', 1),
('青岛农业大学', 1),
('山东第二医科大学', 1),
('山东第一医科大学', 1),
('山东医药大学', 1),
('山东中医药大学', 1),
('济宁医学院', 1),
('山东师范大学', 1),
('曲阜师范大学', 1),
('聊城大学', 1),
('德州学院', 1),
('山东航空学院', 1),
('鲁东大学', 1),
('临沂大学', 1),
('泰山学院', 1),
('济宁学院', 1),
('菏泽学院', 1),
('山东财经大学', 1),
('山东体育学院', 1),
('山东艺术学院', 1),
('枣庄学院', 1),
('山东工艺美术学院', 1),
('青岛大学', 1),
('烟台大学', 1),
('潍坊学院', 1),
('山东警察学院', 1),
('山东交通学院', 1),
('山东工商学院', 1),
('山东女子学院', 1),
('山东石油化工学院', 1),
('山东政法学院', 1),
('齐鲁师范学院', 1),
('山东青年政治学院', 1),
('山东管理学院', 1),
('山东农业工程学院', 1),
('山东商业职业技术大学', 1),
('日照职业技术大学', 1),
('滨州职业技术大学', 1),
('山东科技职业大学', 1),
('淄博职业技术大学', 1),
('康复大学', 1),
('哈尔滨工业大学（威海）', 1),
('北京交通大学（威海）', 1),
('齐鲁医药学院', 1),
('青岛滨海学院', 1),
('烟台南山学院', 1),
('潍坊科技学院', 1),
('山东英才学院', 1),
('青岛恒星科技学院', 1),
('青岛黄海学院', 1),
('山东现代学院', 1),
('山东协和学院', 1),
('山东工程职业技术大学', 1),
('烟台理工学院', 1),
('聊城大学东昌学院', 1),
('青岛城市学院', 1),
('潍坊理工学院', 1),
('山东财经大学燕山学院', 1),
('山东外国语职业技术大学', 1),
('泰山科技学院', 1),
('山东华宇工学院', 1),
('山东外事职业大学', 1),
('青岛工学院', 1),
('青岛农业大学海都学院', 1),
('齐鲁理工学院', 1),
('山东财经大学东方学院', 1),
('烟台科技学院', 1),
('青岛电影学院', 1),
('临沂工学院', 1);

INSERT IGNORE INTO campus (school_id, name, status)
SELECT s.id, c.name, 1
FROM school s
JOIN (
    SELECT '山东大学' AS school_name, '中心校区' AS name UNION ALL
    SELECT '山东大学', '洪家楼校区' UNION ALL
    SELECT '山东大学', '趵突泉校区' UNION ALL
    SELECT '山东大学', '千佛山校区' UNION ALL
    SELECT '山东大学', '软件园校区' UNION ALL
    SELECT '山东大学', '兴隆山校区' UNION ALL
    SELECT '山东大学', '青岛校区' UNION ALL
    SELECT '山东大学', '威海校区' UNION ALL
    SELECT '中国海洋大学', '崂山校区' UNION ALL
    SELECT '中国海洋大学', '鱼山校区' UNION ALL
    SELECT '中国海洋大学', '浮山校区' UNION ALL
    SELECT '中国海洋大学', '西海岸校区' UNION ALL
    SELECT '山东科技大学', '青岛校区' UNION ALL
    SELECT '山东科技大学', '济南校区' UNION ALL
    SELECT '山东科技大学', '泰安校区' UNION ALL
    SELECT '中国石油大学（华东）', '青岛校区' UNION ALL
    SELECT '中国石油大学（华东）', '东营校区' UNION ALL
    SELECT '青岛科技大学', '崂山校区' UNION ALL
    SELECT '青岛科技大学', '四方校区' UNION ALL
    SELECT '青岛科技大学', '高密校区' UNION ALL
    SELECT '青岛科技大学', '济南校区' UNION ALL
    SELECT '济南大学', '主校区' UNION ALL
    SELECT '济南大学', '舜耕校区' UNION ALL
    SELECT '青岛理工大学', '市北校区' UNION ALL
    SELECT '青岛理工大学', '黄岛校区' UNION ALL
    SELECT '青岛理工大学', '临沂校区' UNION ALL
    SELECT '山东建筑大学', '主校区' UNION ALL
    SELECT '齐鲁工业大学', '长清校区' UNION ALL
    SELECT '齐鲁工业大学', '历城校区' UNION ALL
    SELECT '齐鲁工业大学', '菏泽校区' UNION ALL
    SELECT '山东理工大学', '张店校区' UNION ALL
    SELECT '山东农业大学', '岱宗校区' UNION ALL
    SELECT '山东农业大学', '南校区' UNION ALL
    SELECT '山东农业大学', '东校区' UNION ALL
    SELECT '青岛农业大学', '城阳校区' UNION ALL
    SELECT '青岛农业大学', '平度校区' UNION ALL
    SELECT '青岛农业大学', '莱阳校区' UNION ALL
    SELECT '山东第二医科大学', '潍坊校区' UNION ALL
    SELECT '山东第一医科大学', '济南校区' UNION ALL
    SELECT '山东第一医科大学', '泰安校区' UNION ALL
    SELECT '山东医药大学', '滨州校区' UNION ALL
    SELECT '山东医药大学', '烟台校区' UNION ALL
    SELECT '山东中医药大学', '长清校区' UNION ALL
    SELECT '山东中医药大学', '扁鹊校区' UNION ALL
    SELECT '济宁医学院', '太白湖校区' UNION ALL
    SELECT '济宁医学院', '任城校区' UNION ALL
    SELECT '济宁医学院', '日照校区' UNION ALL
    SELECT '山东师范大学', '千佛山校区' UNION ALL
    SELECT '山东师范大学', '长清湖校区' UNION ALL
    SELECT '曲阜师范大学', '曲阜校区' UNION ALL
    SELECT '曲阜师范大学', '日照校区' UNION ALL
    SELECT '聊城大学', '东校区' UNION ALL
    SELECT '聊城大学', '西校区' UNION ALL
    SELECT '德州学院', '主校区' UNION ALL
    SELECT '山东航空学院', '黄河路校区' UNION ALL
    SELECT '山东航空学院', '渤海路校区' UNION ALL
    SELECT '鲁东大学', '主校区' UNION ALL
    SELECT '临沂大学', '主校区' UNION ALL
    SELECT '泰山学院', '主校区' UNION ALL
    SELECT '济宁学院', '主校区' UNION ALL
    SELECT '菏泽学院', '主校区' UNION ALL
    SELECT '山东财经大学', '燕山校区' UNION ALL
    SELECT '山东财经大学', '舜耕校区' UNION ALL
    SELECT '山东财经大学', '圣井校区' UNION ALL
    SELECT '山东财经大学', '明水校区' UNION ALL
    SELECT '山东体育学院', '济南校区' UNION ALL
    SELECT '山东体育学院', '日照校区' UNION ALL
    SELECT '山东艺术学院', '文东校区' UNION ALL
    SELECT '山东艺术学院', '长清校区' UNION ALL
    SELECT '枣庄学院', '主校区' UNION ALL
    SELECT '山东工艺美术学院', '千佛山校区' UNION ALL
    SELECT '山东工艺美术学院', '长清校区' UNION ALL
    SELECT '青岛大学', '浮山校区' UNION ALL
    SELECT '青岛大学', '金家岭校区' UNION ALL
    SELECT '青岛大学', '松山校区' UNION ALL
    SELECT '烟台大学', '主校区' UNION ALL
    SELECT '潍坊学院', '主校区' UNION ALL
    SELECT '山东警察学院', '主校区' UNION ALL
    SELECT '山东交通学院', '无影山校区' UNION ALL
    SELECT '山东交通学院', '长清校区' UNION ALL
    SELECT '山东交通学院', '威海校区' UNION ALL
    SELECT '山东工商学院', '主校区' UNION ALL
    SELECT '山东女子学院', '主校区' UNION ALL
    SELECT '山东石油化工学院', '主校区' UNION ALL
    SELECT '山东政法学院', '主校区' UNION ALL
    SELECT '齐鲁师范学院', '主校区' UNION ALL
    SELECT '山东青年政治学院', '主校区' UNION ALL
    SELECT '山东管理学院', '主校区' UNION ALL
    SELECT '山东农业工程学院', '济南校区' UNION ALL
    SELECT '山东农业工程学院', '齐河校区' UNION ALL
    SELECT '山东农业工程学院', '淄博校区' UNION ALL
    SELECT '山东商业职业技术大学', '主校区' UNION ALL
    SELECT '日照职业技术大学', '主校区' UNION ALL
    SELECT '滨州职业技术大学', '主校区' UNION ALL
    SELECT '山东科技职业大学', '主校区' UNION ALL
    SELECT '淄博职业技术大学', '主校区' UNION ALL
    SELECT '康复大学', '青岛校区' UNION ALL
    SELECT '哈尔滨工业大学（威海）', '威海校区' UNION ALL
    SELECT '北京交通大学（威海）', '威海校区' UNION ALL
    SELECT '齐鲁医药学院', '主校区' UNION ALL
    SELECT '青岛滨海学院', '主校区' UNION ALL
    SELECT '烟台南山学院', '主校区' UNION ALL
    SELECT '潍坊科技学院', '主校区' UNION ALL
    SELECT '山东英才学院', '主校区' UNION ALL
    SELECT '青岛恒星科技学院', '主校区' UNION ALL
    SELECT '青岛黄海学院', '主校区' UNION ALL
    SELECT '山东现代学院', '主校区' UNION ALL
    SELECT '山东协和学院', '主校区' UNION ALL
    SELECT '山东工程职业技术大学', '主校区' UNION ALL
    SELECT '烟台理工学院', '主校区' UNION ALL
    SELECT '聊城大学东昌学院', '主校区' UNION ALL
    SELECT '青岛城市学院', '主校区' UNION ALL
    SELECT '潍坊理工学院', '主校区' UNION ALL
    SELECT '山东财经大学燕山学院', '主校区' UNION ALL
    SELECT '山东外国语职业技术大学', '主校区' UNION ALL
    SELECT '泰山科技学院', '主校区' UNION ALL
    SELECT '山东华宇工学院', '主校区' UNION ALL
    SELECT '山东外事职业大学', '威海校区' UNION ALL
    SELECT '山东外事职业大学', '济南校区' UNION ALL
    SELECT '青岛工学院', '主校区' UNION ALL
    SELECT '青岛农业大学海都学院', '莱阳校区' UNION ALL
    SELECT '齐鲁理工学院', '济南校区' UNION ALL
    SELECT '齐鲁理工学院', '曲阜校区' UNION ALL
    SELECT '山东财经大学东方学院', '泰安校区' UNION ALL
    SELECT '烟台科技学院', '主校区' UNION ALL
    SELECT '青岛电影学院', '主校区' UNION ALL
    SELECT '临沂工学院', '主校区'
) c ON c.school_name = s.name;
