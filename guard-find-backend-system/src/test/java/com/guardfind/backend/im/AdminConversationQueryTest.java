package com.guardfind.backend.im;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.guardfind.backend.im.mapper.AdminMessageMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the real mapper SQL against H2 in MySQL mode. */
class AdminConversationQueryTest {
    SqlSession session;
    AdminMessageMapper mapper;

    @BeforeEach
    void setup() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:conversation_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), source));
        var resource = "mapper/AdminMessageMapper.xml";
        try (var xml = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
        }
        session = new MybatisSqlSessionFactoryBuilder().build(config).openSession();
        mapper = session.getMapper(AdminMessageMapper.class);
        try (var sql = session.getConnection().createStatement()) {
            sql.execute("CREATE TABLE gf_miniapp_user(id BIGINT PRIMARY KEY, nickname VARCHAR(64), deleted BIGINT DEFAULT 0)");
            sql.execute("CREATE TABLE gf_admin_message(id BIGINT PRIMARY KEY, user_id BIGINT, sender_type VARCHAR(10), content VARCHAR(2000), is_read INT, create_time TIMESTAMP)");
            for (int i = 1; i <= 65; i++) {
                sql.execute("INSERT INTO gf_miniapp_user(id,nickname) VALUES(" + i + ",'用户" + i + "')");
                sql.execute("INSERT INTO gf_admin_message VALUES(" + i + "," + i + ",'USER','消息',1,CURRENT_TIMESTAMP)");
            }
            sql.execute("UPDATE gf_miniapp_user SET nickname='上海张先生' WHERE id=1");
            sql.execute("UPDATE gf_admin_message SET is_read=0 WHERE id=1");
        }
    }

    @AfterEach
    void cleanup() {
        if (session != null) session.close();
    }

    @Test
    void nicknameAndIdSearchFindConversationsOutsideFirstPage() {
        var first = mapper.conversationPage(0, 30, null, null, false, null);
        assertEquals(30, first.size());
        assertTrue(first.stream().noneMatch(item -> item.getUserId() == 1));
        var found = mapper.conversationPage(0, 30, "张", null, false, null);
        assertEquals(1, found.size());
        assertEquals(1L, found.get(0).getUserId());
        assertEquals(1, mapper.countConversations("张", null, false));
        assertTrue(mapper.conversationPage(0, 30, "1", 1L, false, null)
            .stream().anyMatch(item -> item.getUserId() == 1L));
    }

    @Test
    void unreadFilteringAndCountHappenBeforePagination() {
        var found = mapper.conversationPage(0, 30, null, null, true, null);
        assertEquals(1, found.size());
        assertEquals(1L, found.get(0).getUserId());
        assertEquals(1, mapper.countConversations(null, null, true));
        assertEquals(0, mapper.countConversations("不存在", null, true));
    }

    @Test
    void cursorContinuesWithoutDuplicatesWhenNewMessagesReorderFirstPage() throws Exception {
        var first = mapper.conversationPage(0, 30, null, null, false, null);
        try (var sql = session.getConnection().createStatement()) {
            sql.execute("INSERT INTO gf_admin_message VALUES(1000,65,'ADMIN','新消息',0,CURRENT_TIMESTAMP)");
        }
        var second = mapper.conversationPage(0, 30, null, null, false, first.get(first.size() - 1).getId());
        assertEquals(30, second.size());
        assertEquals(35L, second.get(0).getUserId());
        assertEquals(6L, second.get(second.size() - 1).getUserId());
        assertEquals(65, mapper.countConversations(null, null, false));
        assertEquals(5, mapper.conversationPage(0, 30, null, null, false, second.get(second.size() - 1).getId()).size());
    }

    @Test
    void unreadConversationUsesLatestReplyEvenWhenUnreadMessageIsOlder() throws Exception {
        try (var sql = session.getConnection().createStatement()) {
            sql.execute("INSERT INTO gf_admin_message VALUES(1000,1,'ADMIN','回复',0,CURRENT_TIMESTAMP)");
        }
        assertEquals("回复", mapper.conversationPage(0, 30, null, null, true, null).get(0).getContent());
        assertTrue(mapper.conversationPage(0, 30, null, null, true, 999L).isEmpty());
    }

    @Test
    void literalWildcardAndQuotesCannotExpandSearch() throws Exception {
        try (var sql = session.getConnection().createStatement()) {
            sql.execute("UPDATE gf_miniapp_user SET nickname='张%_!' WHERE id=1");
        }
        assertEquals(1, mapper.countConversations("张!%!_!!", null, false));
        assertEquals(0, mapper.countConversations("' OR 1=1 --", null, false));
        assertEquals(1, mapper.countConversations("!%", null, false));
    }
}
