/**
 * 运行环境配置。
 * dev：本机调试，直连 Spring Boot；cloud：微信云托管，走 callContainer 内网调用。
 */
export const config = {
  env: 'dev' as 'dev' | 'cloud',
  /** dev 模式下的 API 基地址（开发者工具请勾选「不校验合法域名」） */
  devBaseUrl: 'http://127.0.0.1:8080',
  /**
   * 单次请求超时（毫秒）。
   *
   * 微信默认 60s，对手机端太久：后端卡住时用户会盯着一个永不停的 loading，
   * 分不清是网络慢还是操作没生效（对应审查 P3-11）。
   * 15s 是「比最慢的正常接口还长、又短到用户愿意等」的折中；
   * 超时后按失败提示，用户可以直接重试。
   */
  timeoutMs: 15000,
  /** 云托管容器信息，部署后填写 */
  cloudContainer: {
    service: 'petshop-server',
    pathPrefix: ''
  }
}

export const TOKEN_KEY = 'petshop_token'
export const MEMBER_KEY = 'petshop_member'
export const THEME_KEY = 'petshop_theme'
