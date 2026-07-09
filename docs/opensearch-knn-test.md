# OpenSearch k-NN 验证

当前测试集群使用 HTTPS 和自签名证书，命令需要带 `-k`：

```powershell
$OS = "https://192.168.195.221:9200"
$AUTH = "admin:apexSoft12345"
```

## 1. 检查插件

```powershell
curl.exe -k -u $AUTH "$OS/_cat/plugins?v"
curl.exe -k -u $AUTH "$OS/_plugins/_knn/stats?pretty"
```

需要看到 `opensearch-knn`，中文检索相关环境通常还会有 `analysis-ik`、`analysis-pinyin`。

## 2. 创建测试索引

```powershell
curl.exe -k -u $AUTH -X DELETE "$OS/vector_test"

$body = @'
{
  "settings": {
    "index.knn": true
  },
  "mappings": {
    "properties": {
      "embedding": {
        "type": "knn_vector",
        "dimension": 4
      }
    }
  }
}
'@

$body | curl.exe -k -u $AUTH -X PUT "$OS/vector_test" `
  -H "Content-Type: application/json" `
  --data-binary "@-"
```

## 3. 写入向量

```powershell
@'
{"embedding":[0.12,0.23,0.34,0.45]}
'@ | curl.exe -k -u $AUTH -X POST "$OS/vector_test/_doc/1" -H "Content-Type: application/json" --data-binary "@-"

@'
{"embedding":[0.90,0.80,0.70,0.60]}
'@ | curl.exe -k -u $AUTH -X POST "$OS/vector_test/_doc/2" -H "Content-Type: application/json" --data-binary "@-"

@'
{"embedding":[0.13,0.22,0.31,0.44]}
'@ | curl.exe -k -u $AUTH -X POST "$OS/vector_test/_doc/3" -H "Content-Type: application/json" --data-binary "@-"

curl.exe -k -u $AUTH -X POST "$OS/vector_test/_refresh"
```

## 4. 查询验证

```powershell
$query = @'
{
  "size": 2,
  "query": {
    "knn": {
      "embedding": {
        "vector": [0.11, 0.22, 0.33, 0.44],
        "k": 2
      }
    }
  }
}
'@

$query | curl.exe -k -u $AUTH -X POST "$OS/vector_test/_search?pretty" `
  -H "Content-Type: application/json" `
  --data-binary "@-"

curl.exe -k -u $AUTH "$OS/vector_test/_mapping?pretty"
```

期望结果：Top 2 命中 `_id=1` 和 `_id=3`，mapping 中 `embedding.type` 为 `knn_vector`。

## 5. Faiss + HNSW 验证

```powershell
curl.exe -k -u $AUTH -X DELETE "$OS/vector_hnsw"

$body = @'
{
  "settings": {
    "index.knn": true
  },
  "mappings": {
    "properties": {
      "embedding": {
        "type": "knn_vector",
        "dimension": 4,
        "method": {
          "name": "hnsw",
          "engine": "faiss",
          "space_type": "l2",
          "parameters": {
            "ef_construction": 128,
            "m": 16
          }
        }
      }
    }
  }
}
'@

$body | curl.exe -k -u $AUTH -X PUT "$OS/vector_hnsw" `
  -H "Content-Type: application/json" `
  --data-binary "@-"

curl.exe -k -u $AUTH "$OS/vector_hnsw/_mapping?pretty"
```

mapping 中出现 `engine: faiss`、`name: hnsw` 即表示 HNSW 向量索引创建成功。

## 6. 系统配置

API 文档检索和 MCP OpenSearch 检索都支持 HTTPS 自签名证书：

```yaml
url: https://192.168.195.221:9200
username: admin
password: apexSoft12345
insecure-ssl: true
```

`insecure-ssl: true` 只建议用于开发/测试环境，语义等价于 `curl -k`。生产如果换成正式证书，建议改为 `false`。
