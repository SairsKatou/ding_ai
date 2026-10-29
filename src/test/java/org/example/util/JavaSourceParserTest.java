package org.example.util;

import com.ct.common.core.constant.Constants;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Java 源码方法范围解析单元测试
 *
 * @author zeymo
 */
public class JavaSourceParserTest {

    /**
     * 测试识别方法声明：跳过注解、if/catch/调用/赋值语句，匿名类方法归属外层方法，含接口方法
     */
    @Test
    public void testListMethods() {
        String code = "public class DemoServiceImpl implements DemoService {\n"
                + "    private final Runnable task = () -> run();\n"
                + "    /**\n"
                + "     * 新增\n"
                + "     */\n"
                + "    @Override\n"
                + "    @Transactional(rollbackFor = Exception.class)\n"
                + "    public Long add(DemoDTO dto) {\n"
                + "        if (dto == null) {\n"
                + "            throw new IllegalArgumentException(\"x\");\n"
                + "        }\n"
                + "        try {\n"
                + "            check(dto);\n"
                + "        } catch (Exception e) {\n"
                + "            log.error(\"{}\", e);\n"
                + "        }\n"
                + "        executor.submit(new Runnable() {\n"
                + "            public void run() { save(dto); }\n"
                + "        });\n"
                + "        return dto.getId();\n"
                + "    }\n"
                + "\n"
                + "    private void check(DemoDTO dto) {\n"
                + "        String s = \"}\";\n"
                + "    }\n"
                + "\n"
                + "    public Map<String, List<Long>> group(List<DemoDTO> list) { return null; }\n"
                + "}\n"
                + "interface DemoMapper {\n"
                + "    int insertBatch(@Param(\"list\") List<DemoDTO> list);\n"
                + "}\n";
        String[] lines = code.split("\n");

        List<JavaSourceParser.MethodRange> methods = JavaSourceParser.listMethods(lines);
        List<String> names = methods.stream().map(JavaSourceParser.MethodRange::name).collect(Collectors.toList());

        assertEquals(List.of("add", "check", "group", "insertBatch"), names);
        JavaSourceParser.MethodRange add = methods.get(Constants.ZERO);
        // 起始行包含 Javadoc 与注解，结束行为方法体右括号
        assertEquals(Constants.TWO, add.startLine());
        assertEquals(Constants.TWENTY, add.endLine());
        // 字符串中的 } 不影响方法结束行
        assertEquals(24, methods.get(Constants.ONE).endLine());
        // 接口方法无方法体，结束于分号所在行
        JavaSourceParser.MethodRange insert = methods.get(Constants.THREE);
        assertEquals(insert.declarationLine(), insert.endLine());
    }
}
