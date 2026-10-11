/**
 * 客户端源集：屏幕、面板与渲染。
 *
 * <p>这一集刻意与 main 分开——公共代码因此不可能误引客户端类，方向反过来也受限：客户端只依赖 main 的
 * 公开类型（{@code api} 包与菜单），不伸手进实现细节。</p>
 *
 * <p>两条施工约定（与姊妹项目 AE2 Pattern Disk 同款）：屏幕按 id 取控件与贴图，样式文档
 * （{@code assets/ae2/screens/<mod id>/*.json}）必须已声明该键，否则开屏即抛——改完跑
 * {@code node tools/check-screen-widgets.cjs .} 自检；屏幕里的 id 与 {@code registerClientAction} 的动作名
 * 都是协议，改名等于改协议。</p>
 */
package io.github.lounode.ae2modularcells.client;
