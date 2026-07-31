## 1. 接口描述

接口请求域名： wsa.tencentcloudapi.com 。

联网搜索API，以JSON形式向客户提供搜索结果数据，包含标题、摘要、内容来源url等信息

<div class="rno-api-explorer">
    <div class="rno-api-explorer-inner">
        <div class="rno-api-explorer-hd">
            <div class="rno-api-explorer-title">
                推荐使用 API Explorer
            </div>
            <a href="https://console.cloud.tencent.com/api/explorer?Product=wsa&Version=2025-05-08&Action=SearchPro" class="rno-api-explorer-btn" hotrep="doc.api.explorerbtn"><i class="rno-icon-explorer"></i>点击调试</a>
        </div>
        <div class="rno-api-explorer-body">
            <div class="rno-api-explorer-cont">
                API Explorer 提供了在线调用、签名验证、SDK 代码生成和快速检索接口等能力。您可查看每次调用的请求内容和返回结果以及自动生成 SDK 调用示例。
            </div>
        </div>
    </div>
</div>

## 2. 输入参数

以下请求参数列表仅列出了接口请求参数和部分公共参数，完整公共参数列表见 [公共请求参数](/document/api/1806/121815)。

| 参数名称 | 必选 | 类型 | 描述 |
|---------|---------|---------|---------|
| Action | 是 | String | [公共参数](/document/api/1806/121815)，本接口取值：SearchPro。 |
| Version | 是 | String | [公共参数](/document/api/1806/121815)，本接口取值：2025-05-08。 |
| Region | 否 | String | [公共参数](/document/api/1806/121815)，本接口不需要传递此参数。 |
| Query | 是 | String | <p>搜索词</p><br/>示例值：今天北京的天气 |
| Mode | 否 | Integer | <p>返回结果类型，0-自然检索结果(默认)，1-多模态VR结果，2-混合结果（多模态VR结果+自然检索结果）</p><br/>示例值：2 |
| Site | 否 | String | <p>指定域名站内搜索（用于过滤自然检索结果）<br>注意： mode=1模式下，参数无效；mode=0模式下，对所有结果生效；mode=2模式下，对输出的自然结果生效</p><br/>示例值：zhihu.com |
| FromTime | 否 | Integer | <p>起始时间（用于过滤自然检索结果），精确到秒时间戳格式<br>注意： mode=1模式下，参数无效；mode=0模式下，对所有结果生效；mode=2模式下，对输出的自然结果生效</p><br/>示例值：1745498501 |
| ToTime | 否 | Integer | <p>结束时间（用于过滤自然检索结果），精确到秒时间戳格式<br>注意：mode=1模式下，参数无效；mode=0模式下，对所有结果生效；mode=2模式下，对输出的自然结果生效</p><br/>示例值：1745498501 |
| Cnt | 否 | Integer | <p>cnt=10/20/30/40/50，最多可支持返回50条搜索结果，<strong>仅限尊享版使用</strong></p><br/>示例值：10 |
| Industry | 否 | String | <p>Industry=gov/news/acad/finance，对应党政机关、权威媒体、学术（英文）、金融，<strong>仅限尊享版使用</strong></p><br/>示例值：news |
| Freshness | 否 | String | <p>搜索实效范围（仅旗舰版参数）</p><ul><li>d[N]：最近N天，N取值1-30整数。</li><li>m[N]：最近N月，N取值1-12整数。</li><li>y[N]：最近N年，N取值1-5整数。</li></ul><p>示例说明：</p><ul><li>d1/m1/y1：当天/当月/当年。<br>例如，2026.6.15分别传参d1/m1/y1进行搜索，则搜索结果的时间范围分别为“2026.6.15”/“2026.6”/“2026”，以此类推。</li><li>d/m/y：N值为空时，默认N=1，即等效入参d1/m1/y1。</li><li>未传参时，默认不生效。</li></ul><p>枚举值：</p><ul><li>d7： 最近七天</li><li>m3： 最近三月</li><li>y2： 最近两年</li><li>d： 当天</li><li>m： 当月</li><li>y： 当年</li></ul><br/>示例值：d3 |
| Deeplinks | 否 | Boolean | <p>返回子链信息（仅旗舰版参数）</p><p>子链信息包括&quot;子链标题&quot;和&quot;子链URL&quot;，单个doc最多返回10条子链信息。</p><ul><li>true：返回</li><li>false：不返回</li><li>未传参时默认不返回</li></ul><br/>示例值：true |

## 3. 输出参数

| 参数名称 | 类型 | 描述 |
|---------|---------|---------|
| Query | String | <p>原始查询语</p><br/>示例值：今天北京的天气|
| Pages | Array of String | <p>搜索结果页面详情，格式为json字符串。</p><ul><li><p>title：结果标题</p></li><li><p>date：内容发布时间</p></li><li><p>url：内容发布源url</p></li><li><p>passage：标准摘要</p></li><li><p>content：动态摘要（仅尊享版、旗舰版返回该字段）</p></li><li><p>site：网站名称，部分不知名站点结果可能为空</p></li><li><p>score：相关性得分，取值0～1，越靠近1表示越相关</p></li><li><p>images：图片列表（旗舰版无该出参）</p></li><li><p>pics：图片列表，单个doc返回0～10条（仅旗舰版参数）</p><ul><li>caption：图片描述</li><li>origin_url：源图url地</li></ul></li><li><p>favicon：网站图标链接，部分不知名站点结果可能为空</p></li><li><p>deeplinks：子链信息，单个doc最多返回10条子链信息。（仅旗舰版参数，通过Deeplinks入参控制）</p><ul><li>title：子链标题</li><li>url：子链地址</li></ul></li></ul><br/>示例值：["{\"passage\":\"aaa\"}", "{\"passage\":\"bbb\"}"]|
| Version | String | <p>用户版本：standard/premium/lite/flagship（标准/尊享/轻量/旗舰）</p><br/>示例值：standard|
| Msg | String | <p>提示信息</p><br/>示例值：hit black query|
| RequestId | String | 唯一请求 ID，由服务端生成，每次请求都会返回（若请求因其他原因未能抵达服务端，则该次请求不会获得 RequestId）。定位问题时需要提供该次请求的 RequestId。|

## 4. 示例

### 示例1 请求成功示例

#### 输入示例

```
POST / HTTP/1.1
Host: wsa.tencentcloudapi.com
Content-Type: application/json
X-TC-Action: SearchPro
<公共请求参数>

{
    "Query": "人工智能发展现状"
}
```

#### 输出示例

```json
{
    "Response": {
        "Pages": [
            "{\"title\":\"【新思想引领新征程】人工智能创新加速我国产业转型升级_中央网络安全和信息化委员会办公室\",\"url\":\"https://www.cac.gov.cn/2025-02/26/c_1742272852299173.htm\",\"date\":\"2025/02/26 09:37:00\",\"passage\":\"【新思想引领新征程】人工智能创新加速我国产业转型升级_中央网络安全和信息化委员会办公室\",\"content\":\"【新思想引领新征程】人工智能创新加速我国产业转型升级_中央网络安全和信息化委员会办公室...\",\"site\":\"中国网信网\",\"favicon\":\"https://cdn-yb.icon.qq.com/zuowei_dir/www_cac_gov_cn.ico\",\"score\":0.84889734,\"pics\":[{\"caption\":\"CCTV报道人工智能创新加速我国产业转型升级\",\"origin_url\":\"https://www.cac.gov.cn/rootimages/2025/02/26/1742272852299173-1742272852345396.jpg\"},{\"caption\":\"CCTV13报道工厂内正在搬运黑色料箱的机械臂\",\"origin_url\":\"https://www.cac.gov.cn/rootimages/2025/02/26/1742272852299173-1742272852346371.jpg\"},{\"caption\":\"AI创新文章配图服务器机房\",\"origin_url\":\"https://www.cac.gov.cn/rootimages/2025/02/26/1742272852299173-1742272852347738.jpg\"},{\"caption\":\"CCTV13报道长春市人工智能产业展示\",\"origin_url\":\"https://www.cac.gov.cn/rootimages/2025/02/26/1742272852299173-1742272852348427.jpg\"},{\"caption\":\"央视报道人工智能创新加速产业转型升级\",\"origin_url\":\"https://www.cac.gov.cn/rootimages/2025/02/26/1742272852299173-1742272852350041.jpg\"},{\"caption\":\"CCTV13报道之江实验室建筑及园区\",\"origin_url\":\"https://www.cac.gov.cn/rootimages/2025/02/26/1742272852299173-1742272852350602.jpg\"}]}"
        ],
        "Query": "人工智能发展现状",
        "Version": "flagship",
        "RequestId": "39684d0c-0504-4892-b7b4-0d8f05203007"
    }
}
```


## 5. 开发者资源

### 腾讯云 API 平台

[腾讯云 API 平台](https://cloud.tencent.com/api) 是综合 API 文档、错误码、API Explorer 及 SDK 等资源的统一查询平台，方便您从同一入口查询及使用腾讯云提供的所有 API 服务。

### API Inspector

用户可通过 [API Inspector](https://cloud.tencent.com/document/product/1278/49361) 查看控制台每一步操作关联的 API 调用情况，并自动生成各语言版本的 API 代码，也可前往 [API Explorer](https://cloud.tencent.com/document/product/1278/46697) 进行在线调试。

### SDK

云 API 3.0 提供了配套的开发工具集（SDK），支持多种编程语言，能更方便的调用 API。
* Tencent Cloud SDK 3.0 for Python: [CNB](https://cnb.cool/tencent/cloud/api/sdk/tencentcloud-sdk-python/-/blob/master/tencentcloud/wsa/v20250508/wsa_client.py), [GitHub](https://github.com/TencentCloud/tencentcloud-sdk-python/blob/master/tencentcloud/wsa/v20250508/wsa_client.py), [Gitee](https://gitee.com/TencentCloud/tencentcloud-sdk-python/blob/master/tencentcloud/wsa/v20250508/wsa_client.py)
* Tencent Cloud SDK 3.0 for Java: [CNB](https://cnb.cool/tencent/cloud/api/sdk/tencentcloud-sdk-java/-/blob/master/src/main/java/com/tencentcloudapi/wsa/v20250508/WsaClient.java), [GitHub](https://github.com/TencentCloud/tencentcloud-sdk-java/blob/master/src/main/java/com/tencentcloudapi/wsa/v20250508/WsaClient.java), [Gitee](https://gitee.com/TencentCloud/tencentcloud-sdk-java/blob/master/src/main/java/com/tencentcloudapi/wsa/v20250508/WsaClient.java)
* Tencent Cloud SDK 3.0 for PHP: [CNB](https://cnb.cool/tencent/cloud/api/sdk/tencentcloud-sdk-php/-/blob/master/src/TencentCloud/Wsa/V20250508/WsaClient.php), [GitHub](https://github.com/TencentCloud/tencentcloud-sdk-php/blob/master/src/TencentCloud/Wsa/V20250508/WsaClient.php), [Gitee](https://gitee.com/TencentCloud/tencentcloud-sdk-php/blob/master/src/TencentCloud/Wsa/V20250508/WsaClient.php)
* Tencent Cloud SDK 3.0 for Go: [CNB](https://cnb.cool/tencent/cloud/api/sdk/tencentcloud-sdk-go/-/blob/master/tencentcloud/wsa/v20250508/client.go), [GitHub](https://github.com/TencentCloud/tencentcloud-sdk-go/blob/master/tencentcloud/wsa/v20250508/client.go), [Gitee](https://gitee.com/TencentCloud/tencentcloud-sdk-go/blob/master/tencentcloud/wsa/v20250508/client.go)
* Tencent Cloud SDK 3.0 for Node.js: [CNB](https://cnb.cool/tencent/cloud/api/sdk/tencentcloud-sdk-nodejs/-/blob/master/src/services/wsa/v20250508/wsa_client.ts), [GitHub](https://github.com/TencentCloud/tencentcloud-sdk-nodejs/blob/master/src/services/wsa/v20250508/wsa_client.ts), [Gitee](https://gitee.com/TencentCloud/tencentcloud-sdk-nodejs/blob/master/src/services/wsa/v20250508/wsa_client.ts)
* Tencent Cloud SDK 3.0 for .NET: [CNB](https://cnb.cool/tencent/cloud/api/sdk/tencentcloud-sdk-dotnet/-/blob/master/TencentCloud/Wsa/V20250508/WsaClient.cs), [GitHub](https://github.com/TencentCloud/tencentcloud-sdk-dotnet/blob/master/TencentCloud/Wsa/V20250508/WsaClient.cs), [Gitee](https://gitee.com/TencentCloud/tencentcloud-sdk-dotnet/blob/master/TencentCloud/Wsa/V20250508/WsaClient.cs)
* Tencent Cloud SDK 3.0 for C++: [CNB](https://cnb.cool/tencent/cloud/api/sdk/tencentcloud-sdk-cpp/-/blob/master/wsa/src/v20250508/WsaClient.cpp), [GitHub](https://github.com/TencentCloud/tencentcloud-sdk-cpp/blob/master/wsa/src/v20250508/WsaClient.cpp), [Gitee](https://gitee.com/TencentCloud/tencentcloud-sdk-cpp/blob/master/wsa/src/v20250508/WsaClient.cpp)
* Tencent Cloud SDK 3.0 for Ruby: [CNB](https://cnb.cool/tencent/cloud/api/sdk/tencentcloud-sdk-ruby/-/blob/master/tencentcloud-sdk-wsa/lib/v20250508/client.rb), [GitHub](https://github.com/TencentCloud/tencentcloud-sdk-ruby/blob/master/tencentcloud-sdk-wsa/lib/v20250508/client.rb), [Gitee](https://gitee.com/TencentCloud/tencentcloud-sdk-ruby/blob/master/tencentcloud-sdk-wsa/lib/v20250508/client.rb)

### 命令行工具

* [Tencent Cloud CLI 3.0](https://cloud.tencent.com/document/product/440/6176)

## 6. 错误码

以下仅列出了接口业务逻辑相关的错误码，其他错误码详见 [公共错误码](/document/api/1806/121820#.E5.85.AC.E5.85.B1.E9.94.99.E8.AF.AF.E7.A0.81)。

| 错误码 | 描述 |
|---------|---------|
| InternalError | 内部错误。 |
| InvalidParameter | 参数错误。 |
| RequestLimitExceeded | 请求的次数超过了频率限制。 |
| ResourceNotFound | 用户资源未开通。 |
| ResourceUnavailable | 用户资源不可用。 |
| UnauthorizedOperation | 未授权操作。 |
