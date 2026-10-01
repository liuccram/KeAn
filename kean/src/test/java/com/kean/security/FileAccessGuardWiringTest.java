package com.kean.security;

import com.kean.mapper.ChatMessageMapper;
import com.kean.mapper.ChatSessionMapper;
import com.kean.mapper.SubstituteApplicationMapper;
import com.kean.mapper.SubstituteTaskMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配回归测试：验证 Spring 真的能把 {@link FileAccessGuard} 实例化出来。
 *
 * <p>为什么单独写一个：{@code FileAccessGuard} 有两个构造器（公开的 4 参构造器 + 给测试注入
 * {@code Ticker} 的包私有 5 参构造器）。Spring 只在"有且仅有一个构造器"时才自动选用它；
 * 构造器多于一个且都没标注 {@code @Autowired} 时，它会退回无参构造器并抛
 * {@code No default constructor found}，整个应用启动失败。</p>
 *
 * <p>而现有的 {@code FileControllerTest} 用 Mockito 把本类替换成了替身，
 * {@code FileAccessGuardTest} 直接用包私有构造器 new 出来，两边都不经过 Spring 的构造器选择，
 * 所以单元测试全绿、应用却起不来。这个测试补上的正是这段空白。</p>
 *
 * <p>不加载完整应用上下文、不连库：只注册 4 个 mapper 替身和被测组件本身。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileAccessGuardWiringTest {

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @Mock
    private ChatSessionMapper chatSessionMapper;

    @Mock
    private SubstituteTaskMapper substituteTaskMapper;

    @Mock
    private SubstituteApplicationMapper substituteApplicationMapper;

    @Test
    @DisplayName("Spring 能选出 FileAccessGuard 的构造器并完成注入")
    void springCanInstantiateFileAccessGuard() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ChatMessageMapper.class, () -> chatMessageMapper);
            context.registerBean(ChatSessionMapper.class, () -> chatSessionMapper);
            context.registerBean(SubstituteTaskMapper.class, () -> substituteTaskMapper);
            context.registerBean(SubstituteApplicationMapper.class, () -> substituteApplicationMapper);
            context.register(FileAccessGuard.class);
            context.refresh();

            FileAccessGuard guard = context.getBean(FileAccessGuard.class);
            assertThat(guard).isNotNull();
            // 顺带确认拿到的是功能完整的实例，而不是某种空壳代理
            assertThat(guard.canRead("avatar/1/x.jpg", null, false)).isTrue();
            assertThat(guard.canRead("report/1/x.jpg", null, false)).isFalse();
        }
    }
}
