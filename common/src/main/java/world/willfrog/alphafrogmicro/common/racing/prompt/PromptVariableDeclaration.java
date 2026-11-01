package world.willfrog.alphafrogmicro.common.racing.prompt;

import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

import static world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked.PROMPT_VARIABLE_INVALID;

/**
 * 变量规则随正文版本固定，可选变量声明默认文本。
 * 名称与双花括号内原文精确匹配；空串规则允许空格和换行，模板仅执行变量替换。
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
