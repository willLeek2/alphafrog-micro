/**
 * 控制面：创建 / 暂停 / 取消 / 查询之外，还负责「现在能不能再开一条 Run」。
 * 门面层仍会直接调用执行管线和长工具服务；上层并不是只跟控制面说话。
 */
package world.willfrog.agentlangchain.control;
