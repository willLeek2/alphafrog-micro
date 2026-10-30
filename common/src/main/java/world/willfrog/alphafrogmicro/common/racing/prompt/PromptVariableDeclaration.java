package world.willfrog.alphafrogmicro.common.racing.prompt;

import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

import static world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked.PROMPT_VARIABLE_INVALID;

/**
 * 随正文版本固定的变量规则。可选变量必须声明默认文本；空串规则不拒绝空格或换行。
 * 名称与双花括号中的原文精确匹配，不裁剪名称，不解释表达式或执行循环。
 */
public record PromptVariableDeclaration(
        String name,
        boolean required,
        boolean allowEmpty,
        String defaultValue) {

    public PromptVariableDeclaration {
        if (!isValidName(name)) {
            throw new ModelSendBlockedException(PROMPT_VARIABLE_INVALID, "variables");
        }
        if ((!required && defaultValue == null)
                || (defaultValue != null && defaultValue.isEmpty() && !allowEmpty)) {
            throw new ModelSendBlockedException(PROMPT_VARIABLE_INVALID, name);
        }
    }

    static boolean isValidName(String name) {
        return name != null && !name.isBlank()
                && name.indexOf('{') < 0 && name.indexOf('}') < 0
                && name.chars().noneMatch(Character::isISOControl);
    }

    @Override
    public String toString() {
        return "PromptVariableDeclaration[name=" + name + ", required=" + required
                + ", allowEmpty=" + allowEmpty + ", hasDefault=" + (defaultValue != null) + "]";
    }
}
