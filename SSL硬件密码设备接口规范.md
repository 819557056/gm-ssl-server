# SSL硬件密码设备接口规范

## 文档说明

本文档基于**SSL/TLS协议**（特别是国密TLCP协议）的完整流程分析，定义了硬件密码设备（HSM/加密机）必须提供的密码学操作接口。

**适用场景**：
- 国密SSL/TLS服务器开发
- TLCP v1.1 协议实现
- 等保三级/商密认证项目
- 金融、政务等高安全等级系统

**标准依据**：
- PKCS#11 v2.40 规范
- GM/T 0003-2012 (SM2椭圆曲线公钥密码算法)
- GM/T 0004-2012 (SM3密码杂凑算法)
- GM/T 0002-2012 (SM4分组密码算法)
- GM/T 0024-2014 (TLCP协议规范)
- RFC 8446 (TLS 1.3)

---

## 目录

1. [SSL协议密码操作分析](#一ssl协议密码操作分析)
2. [接口设计原则](#二接口设计原则)
3. [密钥管理接口](#三密钥管理接口)
4. [非对称密码接口](#四非对称密码接口)
5. [对称密码接口](#五对称密码接口)
6. [摘要与MAC接口](#六摘要与mac接口)
7. [随机数生成接口](#七随机数生成接口)
8. [会话管理接口](#八会话管理接口)
9. [证书管理接口](#九证书管理接口)
10. [设备管理接口](#十设备管理接口)
11. [完整接口汇总](#十一完整接口汇总)
12. [Java JCE Provider实现示例](#十二java-jce-provider实现示例)

---

## 一、SSL协议密码操作分析

### 1.1 TLCP握手阶段密码操作

```
阶段                  操作                    算法          硬件支持   优先级
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. ClientHello      生成随机数               TRNG          必须      P0
                    
2. ServerHello      生成随机数               TRNG          必须      P0
   ServerCertificate                                                  
   
3. CertificateVerify 签名生成               SM2-Sign      必须      P0
                    (服务器身份认证)
                    
4. ClientKeyExchange 非对称解密             SM2-Decrypt   必须      P0
                    (预主密钥解密)
                    
5. 会话密钥派生      密钥派生函数            PRF/KDF       建议      P1
                    (主密钥→会话密钥)
                    
6. Finished         HMAC计算                HMAC-SM3      建议      P1
                    (握手完整性验证)
                    
7. 应用数据         对称加密/解密           SM4-CBC/GCM   建议      P1
                    (数据保护)
                    
8. 会话重协商        签名生成                SM2-Sign      必须      P0
                    
9. 证书验证         签名验证                SM2-Verify    可选      P2
                    (客户端证书)
```

### 1.2 密码操作详细映射

| SSL/TLS操作 | 密码算法 | 输入 | 输出 | 是否涉及私钥 | 硬件必要性 |
|------------|---------|------|------|------------|-----------|
| **握手阶段** |
| 生成随机数 | TRNG | 长度(字节) | 随机字节序列 | ❌ | ⭐⭐⭐⭐⭐ |
| 服务器身份证明 | SM2签名 | 握手摘要 | 签名值(r,s) | ✅ 签名私钥 | ⭐⭐⭐⭐⭐ |
| 客户端密钥交换 | SM2解密 | 密文 | 预主密钥 | ✅ 加密私钥 | ⭐⭐⭐⭐⭐ |
| 客户端身份证明 | SM2验签 | 签名+数据 | 布尔值 | ❌ | ⭐⭐ |
| 主密钥派生 | PRF | 预主密钥+随机数 | 主密钥 | ❌ | ⭐⭐⭐⭐ |
| 会话密钥派生 | PRF | 主密钥+随机数 | 会话密钥集 | ❌ | ⭐⭐⭐⭐ |
| **应用数据阶段** |
| 数据加密 | SM4-CBC | 明文+密钥+IV | 密文 | ❌ | ⭐⭐⭐⭐ |
| 数据解密 | SM4-CBC | 密文+密钥+IV | 明文 | ❌ | ⭐⭐⭐⭐ |
| MAC计算 | HMAC-SM3 | 数据+密钥 | MAC值 | ❌ | ⭐⭐⭐ |
| MAC验证 | HMAC-SM3 | 数据+密钥+MAC | 布尔值 | ❌ | ⭐⭐⭐ |
| **密钥管理** |
| 生成密钥对 | SM2 | 域参数 | 公钥+私钥句柄 | ✅ | ⭐⭐⭐⭐⭐ |
| 导入密钥 | - | 密钥数据 | 密钥句柄 | ✅ | ⭐⭐⭐⭐ |
| 导出公钥 | - | 私钥句柄 | 公钥数据 | ❌ | ⭐⭐⭐⭐ |
| 销毁密钥 | - | 密钥句柄 | 成功/失败 | ✅ | ⭐⭐⭐⭐ |

### 1.3 核心结论

**硬件设备必须提供的核心能力**：

1. **密钥管理** (5个接口)
   - 生成SM2/RSA密钥对
   - 导入密钥（加密导入）
   - 导出公钥
   - 销毁密钥
   - 获取密钥属性

2. **非对称密码** (4个接口)
   - SM2签名
   - SM2验签
   - SM2加密
   - SM2解密

3. **对称密码** (6个接口)
   - SM4加密（CBC/GCM/ECB模式）
   - SM4解密（CBC/GCM/ECB模式）
   - 密钥派生（PRF/KDF）

4. **摘要与MAC** (4个接口)
   - SM3摘要计算
   - HMAC-SM3计算
   - HMAC-SM3验证
   - 更新摘要（流式）

5. **随机数** (2个接口)
   - 生成真随机数
   - 生成伪随机数

6. **会话管理** (4个接口)
   - 打开会话
   - 关闭会话
   - 登录/登出
   - 获取会话信息

7. **证书管理** (3个接口)
   - 导入证书
   - 查询证书
   - 删除证书

8. **设备管理** (4个接口)
   - 初始化设备
   - 获取设备信息
   - 获取设备能力
   - 健康检查

---

## 二、接口设计原则

### 2.1 设计原则

1. **安全性优先**
   - 私钥永不离开硬件设备
   - 敏感数据加密传输
   - 强制访问控制（PIN/密码）
   - 操作审计日志

2. **标准兼容性**
   - 遵循PKCS#11标准
   - 支持JCE Provider接口
   - 兼容国密和国际算法

3. **性能优化**
   - 支持会话复用
   - 批量操作接口
   - 异步操作支持

4. **易用性**
   - 清晰的错误码定义
   - 详细的参数说明
   - 完善的异常处理

### 2.2 通用数据类型

```java
/**
 * 会话句柄
 */
typedef long SessionHandle;

/**
 * 密钥句柄（不透明类型，外部不可见密钥内容）
 */
typedef long KeyHandle;

/**
 * 对象句柄（证书、数据对象等）
 */
typedef long ObjectHandle;

/**
 * 算法标识
 */
enum AlgorithmType {
    SM2,           // 国密SM2椭圆曲线
    SM3,           // 国密SM3摘要
    SM4_ECB,       // 国密SM4-ECB模式
    SM4_CBC,       // 国密SM4-CBC模式
    SM4_GCM,       // 国密SM4-GCM模式
    RSA_2048,      // RSA 2048位
    AES_128_CBC,   // AES-128-CBC
    SHA256,        // SHA-256摘要
    HMAC_SM3,      // HMAC-SM3
    PRF_TLS12      // TLS 1.2 PRF
}

/**
 * 密钥类型
 */
enum KeyType {
    PRIVATE_KEY,   // 私钥
    PUBLIC_KEY,    // 公钥
    SECRET_KEY     // 对称密钥
}

/**
 * 错误码
 */
enum ErrorCode {
    SUCCESS = 0x00000000,
    GENERAL_ERROR = 0x00000001,
    SESSION_INVALID = 0x00000002,
    KEY_NOT_FOUND = 0x00000003,
    PIN_INCORRECT = 0x00000004,
    PIN_LOCKED = 0x00000005,
    INSUFFICIENT_BUFFER = 0x00000006,
    OPERATION_NOT_PERMITTED = 0x00000007,
    ALGORITHM_NOT_SUPPORTED = 0x00000008,
    KEY_TYPE_MISMATCH = 0x00000009,
    SIGNATURE_INVALID = 0x0000000A,
    DEVICE_ERROR = 0x0000000B,
    MEMORY_ERROR = 0x0000000C
}
```

### 2.3 通用返回结构

```java
/**
 * 统一返回结构
 */
struct Result<T> {
    ErrorCode errorCode;      // 错误码
    String errorMessage;      // 错误描述
    T data;                   // 返回数据
    long timestamp;           // 时间戳
}
```

---

## 三、密钥管理接口

### 3.1 生成密钥对

**功能描述**：在硬件设备中生成非对称密钥对（SM2/RSA），私钥永不离开设备。

**接口定义**：
```java
/**
 * 生成非对称密钥对
 * 
 * @param session 会话句柄
 * @param algorithm 算法类型（SM2/RSA_2048等）
 * @param keyLabel 密钥标签/别名（用于后续引用）
 * @param exportable 公钥是否可导出（私钥始终不可导出）
 * @param signUsage 是否用于签名
 * @param encryptUsage 是否用于加密
 * @return Result包含：
 *   - privateKeyHandle: 私钥句柄（不透明）
 *   - publicKeyHandle: 公钥句柄
 *   - publicKeyData: 公钥数据（如需要）
 */
Result<KeyPair> generateKeyPair(
    SessionHandle session,
    AlgorithmType algorithm,
    String keyLabel,
    boolean exportable,
    boolean signUsage,
    boolean encryptUsage
);

struct KeyPair {
    KeyHandle privateKeyHandle;
    KeyHandle publicKeyHandle;
    byte[] publicKeyData;  // SM2: 04||X||Y (65字节), RSA: PKCS#1格式
}
```

**SSL应用场景**：
- 服务器初始化时生成签名密钥对
- 服务器初始化时生成加密密钥对（TLCP需要两对密钥）

**调用示例**：
```java
// 生成SM2签名密钥对
Result<KeyPair> result = hsm.generateKeyPair(
    session,
    AlgorithmType.SM2,
    "server_sign_key",  // 别名
    false,              // 私钥不可导出
    true,               // 用于签名
    false               // 不用于加密
);

KeyHandle signPrivateKey = result.data.privateKeyHandle;
byte[] signPublicKey = result.data.publicKeyData;
```

---

### 3.2 导入密钥

**功能描述**：将外部密钥安全导入硬件设备（通常使用加密导入）。

**接口定义**：
```java
/**
 * 导入密钥到硬件设备
 * 
 * @param session 会话句柄
 * @param keyType 密钥类型（私钥/公钥/对称密钥）
 * @param algorithm 算法类型
 * @param keyLabel 密钥标签
 * @param keyData 密钥数据（对于私钥，必须经过加密保护）
 * @param wrapKeyHandle 包装密钥句柄（用于解密keyData，0表示明文导入）
 * @param exportable 是否可导出
 * @return Result包含：
 *   - keyHandle: 导入后的密钥句柄
 */
Result<KeyHandle> importKey(
    SessionHandle session,
    KeyType keyType,
    AlgorithmType algorithm,
    String keyLabel,
    byte[] keyData,
    KeyHandle wrapKeyHandle,
    boolean exportable
);
```

**SSL应用场景**：
- 从PKCS#12文件迁移密钥到硬件设备
- 导入CA根证书对应的公钥

**安全要求**：
- 私钥必须使用KEK（Key Encryption Key）加密后导入
- 明文导入仅限测试环境

---

### 3.3 导出公钥

**功能描述**：导出公钥数据（私钥永不可导出）。

**接口定义**：
```java
/**
 * 导出公钥
 * 
 * @param session 会话句柄
 * @param keyHandle 密钥句柄（必须是公钥或密钥对的公钥部分）
 * @param format 导出格式（RAW/PKCS1/SPKI）
 * @return Result包含：
 *   - publicKeyData: 公钥字节数组
 */
Result<byte[]> exportPublicKey(
    SessionHandle session,
    KeyHandle keyHandle,
    KeyFormat format
);

enum KeyFormat {
    RAW,    // 原始格式：SM2为04||X||Y
    PKCS1,  // PKCS#1格式（RSA）
    SPKI    // SubjectPublicKeyInfo格式（X.509）
}
```

**SSL应用场景**：
- 生成CSR（证书签名请求）时需要公钥
- 客户端连接时向对端发送公钥

---

### 3.4 销毁密钥

**功能描述**：安全销毁硬件设备中的密钥。

**接口定义**：
```java
/**
 * 销毁密钥
 * 
 * @param session 会话句柄
 * @param keyHandle 密钥句柄
 * @return Result包含：
 *   - destroyed: 是否成功销毁
 */
Result<Boolean> destroyKey(
    SessionHandle session,
    KeyHandle keyHandle
);
```

**SSL应用场景**：
- 会话密钥使用完毕后销毁
- 临时密钥的生命周期管理

---

### 3.5 获取密钥属性

**功能描述**：查询密钥的属性信息（不包含密钥内容）。

**接口定义**：
```java
/**
 * 获取密钥属性
 * 
 * @param session 会话句柄
 * @param keyHandle 密钥句柄
 * @return Result包含密钥属性
 */
Result<KeyAttributes> getKeyAttributes(
    SessionHandle session,
    KeyHandle keyHandle
);

struct KeyAttributes {
    String label;              // 密钥标签
    KeyType keyType;           // 密钥类型
    AlgorithmType algorithm;   // 算法类型
    int keySize;               // 密钥长度（位）
    boolean exportable;        // 是否可导出
    boolean signUsage;         // 签名用途
    boolean encryptUsage;      // 加密用途
    long creationTime;         // 创建时间
    long expirationTime;       // 过期时间（0表示永不过期）
}
```

---

### 3.6 查找密钥

**功能描述**：根据标签或属性查找密钥。

**接口定义**：
```java
/**
 * 查找密钥
 * 
 * @param session 会话句柄
 * @param keyLabel 密钥标签（null表示查找所有）
 * @param keyType 密钥类型过滤（null表示不过滤）
 * @return Result包含密钥句柄列表
 */
Result<List<KeyHandle>> findKeys(
    SessionHandle session,
    String keyLabel,
    KeyType keyType
);
```

**SSL应用场景**：
- 根据配置的keyAlias查找对应的签名/加密私钥

---

## 四、非对称密码接口

### 4.1 签名生成

**功能描述**：使用私钥对数据进行数字签名（SM2/RSA）。

**接口定义**：
```java
/**
 * 生成数字签名
 * 
 * @param session 会话句柄
 * @param privateKeyHandle 私钥句柄（必须具有签名用途）
 * @param algorithm 签名算法（SM2/RSA_PSS等）
 * @param digestAlgorithm 摘要算法（SM3/SHA256等）
 * @param data 待签名数据（可以是原始数据或摘要）
 * @param preHashed 是否已经预先计算摘要
 * @return Result包含：
 *   - signature: 签名值
 *     - SM2: ASN.1 DER编码的 SEQUENCE { r INTEGER, s INTEGER }
 *     - RSA: PKCS#1 v1.5或PSS格式
 */
Result<byte[]> sign(
    SessionHandle session,
    KeyHandle privateKeyHandle,
    AlgorithmType algorithm,
    AlgorithmType digestAlgorithm,
    byte[] data,
    boolean preHashed
);
```

**SSL应用场景**：
- **ServerKeyExchange**：服务器对交换参数签名
- **CertificateVerify**：证明拥有证书对应的私钥
- **Finished消息**：对握手摘要签名

**调用示例**：
```java
// SSL握手中的签名操作
byte[] handshakeHash = computeHandshakeHash(); // 握手消息的SM3摘要

Result<byte[]> signResult = hsm.sign(
    session,
    serverSignKeyHandle,
    AlgorithmType.SM2,
    AlgorithmType.SM3,
    handshakeHash,
    true  // 已经是摘要值
);

byte[] signature = signResult.data;
// signature格式: 0x30 || len || 0x02 || r_len || r || 0x02 || s_len || s
```

**性能要求**：
- 单次签名延迟：< 10ms（PCI-E卡）/ < 50ms（网络加密机）
- 并发签名TPS：> 1000（高性能设备）

---

### 4.2 签名验证

**功能描述**：使用公钥验证数字签名。

**接口定义**：
```java
/**
 * 验证数字签名
 * 
 * @param session 会话句柄
 * @param publicKeyHandle 公钥句柄或证书句柄
 * @param algorithm 签名算法
 * @param digestAlgorithm 摘要算法
 * @param data 原始数据或摘要
 * @param signature 签名值
 * @param preHashed 是否已预先计算摘要
 * @return Result包含：
 *   - valid: 签名是否有效
 */
Result<Boolean> verify(
    SessionHandle session,
    KeyHandle publicKeyHandle,
    AlgorithmType algorithm,
    AlgorithmType digestAlgorithm,
    byte[] data,
    byte[] signature,
    boolean preHashed
);
```

**SSL应用场景**：
- 验证客户端证书签名（双向认证）
- 验证CA签发的证书链

**注意事项**：
- 签名验证可在软件中完成，硬件支持可提升性能
- 建议优先使用硬件验签以防止侧信道攻击

---

### 4.3 非对称加密

**功能描述**：使用公钥加密数据（SM2/RSA）。

**接口定义**：
```java
/**
 * 非对称加密
 * 
 * @param session 会话句柄
 * @param publicKeyHandle 公钥句柄
 * @param algorithm 加密算法（SM2/RSA_OAEP）
 * @param plaintext 明文数据
 * @return Result包含：
 *   - ciphertext: 密文
 *     - SM2: C1||C3||C2格式（C1=64字节,C3=32字节,C2=len(plaintext)）
 *     - RSA: PKCS#1 v1.5或OAEP格式
 */
Result<byte[]> encrypt(
    SessionHandle session,
    KeyHandle publicKeyHandle,
    AlgorithmType algorithm,
    byte[] plaintext
);
```

**SSL应用场景**：
- 一般不在SSL握手中使用（SSL使用ECDHE/DHE密钥交换）
- 传统RSA密钥交换模式下加密预主密钥

**SM2加密格式**：
```
C1 = 04 || x1 || y1  (65字节，随机点)
C2 = ciphertext      (与明文等长)
C3 = SM3(x2||M||y2)  (32字节，MAC)
输出: C1 || C3 || C2 或 C1 || C2 || C3（根据标准）
```

---

### 4.4 非对称解密

**功能描述**：使用私钥解密数据（SM2/RSA）。

**接口定义**：
```java
/**
 * 非对称解密
 * 
 * @param session 会话句柄
 * @param privateKeyHandle 私钥句柄（必须具有解密用途）
 * @param algorithm 解密算法
 * @param ciphertext 密文数据
 * @return Result包含：
 *   - plaintext: 明文
 */
Result<byte[]> decrypt(
    SessionHandle session,
    KeyHandle privateKeyHandle,
    AlgorithmType algorithm,
    byte[] ciphertext
);
```

**SSL应用场景**：
- **ClientKeyExchange（TLCP）**：解密客户端发送的预主密钥
  ```
  客户端: 用服务器加密公钥加密 → 发送密文
  服务器: 用加密私钥解密 → 获得预主密钥
  ```

**调用示例**：
```java
// TLCP协议中解密预主密钥
byte[] encryptedPreMasterSecret = clientKeyExchange.getEncrypted();

Result<byte[]> decryptResult = hsm.decrypt(
    session,
    serverEncKeyHandle,  // 加密私钥句柄
    AlgorithmType.SM2,
    encryptedPreMasterSecret
);

byte[] preMasterSecret = decryptResult.data; // 48字节
```

**性能要求**：
- 单次解密延迟：< 15ms（PCI-E卡）
- 这是SSL握手的关键路径，性能影响大

---

## 五、对称密码接口

### 5.1 对称加密

**功能描述**：使用对称密钥加密数据（SM4/AES）。

**接口定义**：
```java
/**
 * 对称加密
 * 
 * @param session 会话句柄
 * @param keyHandle 对称密钥句柄
 * @param algorithm 算法模式（SM4_CBC/SM4_GCM/AES_128_CBC）
 * @param iv 初始化向量（CBC/GCM模式需要，ECB模式为null）
 * @param plaintext 明文数据
 * @param padding 填充方式（PKCS7/NONE）
 * @return Result包含：
 *   - ciphertext: 密文
 *   - authTag: 认证标签（仅GCM模式）
 */
Result<EncryptResult> symmetricEncrypt(
    SessionHandle session,
    KeyHandle keyHandle,
    AlgorithmType algorithm,
    byte[] iv,
    byte[] plaintext,
    PaddingType padding
);

struct EncryptResult {
    byte[] ciphertext;
    byte[] authTag;  // GCM模式的认证标签（16字节）
}

enum PaddingType {
    NONE,     // 无填充（数据必须是块大小的整数倍）
    PKCS7,    // PKCS#7填充
    ISO10126  // ISO 10126填充
}
```

**SSL应用场景**：
- **应用数据加密**：使用协商的会话密钥加密应用层数据
  ```
  TLCP_ECC_SM4_CBC_SM3: 使用SM4-CBC加密
  TLCP_ECC_SM4_GCM_SM3: 使用SM4-GCM加密
  ```

**调用示例**：
```java
// SSL记录层加密
byte[] applicationData = "Hello, World!".getBytes();
byte[] iv = generateRandomIV(16); // 16字节IV

Result<EncryptResult> encResult = hsm.symmetricEncrypt(
    session,
    clientWriteKeyHandle,  // 会话密钥
    AlgorithmType.SM4_CBC,
    iv,
    applicationData,
    PaddingType.PKCS7
);

byte[] encryptedRecord = encResult.data.ciphertext;
```

**性能要求**：
- 吞吐量：> 100MB/s（硬件实现）
- 延迟：< 1ms（小数据块）

---

### 5.2 对称解密

**功能描述**：使用对称密钥解密数据。

**接口定义**：
```java
/**
 * 对称解密
 * 
 * @param session 会话句柄
 * @param keyHandle 对称密钥句柄
 * @param algorithm 算法模式
 * @param iv 初始化向量
 * @param ciphertext 密文数据
 * @param authTag 认证标签（仅GCM模式）
 * @param padding 填充方式
 * @return Result包含：
 *   - plaintext: 明文
 *   - authValid: 认证是否通过（仅GCM模式）
 */
Result<DecryptResult> symmetricDecrypt(
    SessionHandle session,
    KeyHandle keyHandle,
    AlgorithmType algorithm,
    byte[] iv,
    byte[] ciphertext,
    byte[] authTag,
    PaddingType padding
);

struct DecryptResult {
    byte[] plaintext;
    boolean authValid;  // GCM认证结果
}
```

**SSL应用场景**：
- 解密接收到的SSL记录

---

### 5.3 密钥派生（PRF）

**功能描述**：实现SSL/TLS的PRF（伪随机函数），用于主密钥和会话密钥派生。

**接口定义**：
```java
/**
 * 密钥派生函数（PRF - Pseudo-Random Function）
 * 实现TLS PRF: PRF(secret, label, seed) = P_hash(secret, label + seed)
 * 
 * @param session 会话句柄
 * @param secretKeyHandle 密钥句柄（主密钥或预主密钥）
 * @param algorithm PRF算法（PRF_TLS12使用HMAC-SM3或HMAC-SHA256）
 * @param label 标签字符串（如"master secret"、"key expansion"）
 * @param seed 种子数据（通常是客户端和服务器随机数）
 * @param outputLength 输出长度（字节）
 * @return Result包含：
 *   - derivedKey: 派生的密钥材料
 *   - keyHandle: 如果需要保留在硬件中，返回密钥句柄
 */
Result<DeriveResult> deriveKey(
    SessionHandle session,
    KeyHandle secretKeyHandle,
    AlgorithmType algorithm,
    String label,
    byte[] seed,
    int outputLength,
    boolean storeInHardware
);

struct DeriveResult {
    byte[] derivedKeyMaterial;  // 派生的密钥数据
    KeyHandle keyHandle;        // 如果存储在硬件中
}
```

**SSL应用场景**：

**场景1：派生主密钥（Master Secret）**
```java
// TLS 1.2: master_secret = PRF(pre_master_secret, "master secret",
//                               ClientHello.random + ServerHello.random)[0..47]
Result<DeriveResult> masterSecretResult = hsm.deriveKey(
    session,
    preMasterSecretHandle,
    AlgorithmType.PRF_TLS12,
    "master secret",
    concat(clientRandom, serverRandom),
    48,  // 主密钥固定48字节
    true // 保留在硬件中
);
```

**场景2：派生会话密钥（Session Keys）**
```java
// key_block = PRF(master_secret, "key expansion",
//                 server_random + client_random)
Result<DeriveResult> keyBlockResult = hsm.deriveKey(
    session,
    masterSecretHandle,
    AlgorithmType.PRF_TLS12,
    "key expansion",
    concat(serverRandom, clientRandom),
    104, // key_block长度根据密码套件确定
    false
);

// 从key_block中分割出各个密钥：
// client_write_MAC_key[32]
// server_write_MAC_key[32]
// client_write_key[16]
// server_write_key[16]
// client_write_IV[16]
// server_write_IV[16]
```

**PRF算法实现**：
```
TLS 1.2 PRF (HMAC-SM3):
P_SM3(secret, seed) = HMAC_SM3(secret, A(1) + seed) +
                      HMAC_SM3(secret, A(2) + seed) +
                      HMAC_SM3(secret, A(3) + seed) + ...
其中：
A(0) = seed
A(i) = HMAC_SM3(secret, A(i-1))
```

---

### 5.4 密钥封装/解封

**功能描述**：使用KEK（Key Encryption Key）封装会话密钥。

**接口定义**：
```java
/**
 * 密钥封装（Key Wrap）
 * 
 * @param session 会话句柄
 * @param kekHandle KEK密钥句柄
 * @param targetKeyHandle 待封装的密钥句柄
 * @param algorithm 封装算法（SM4_WRAP/AES_WRAP）
 * @return Result包含：
 *   - wrappedKey: 封装后的密钥数据
 */
Result<byte[]> wrapKey(
    SessionHandle session,
    KeyHandle kekHandle,
    KeyHandle targetKeyHandle,
    AlgorithmType algorithm
);

/**
 * 密钥解封（Key Unwrap）
 * 
 * @param session 会话句柄
 * @param kekHandle KEK密钥句柄
 * @param wrappedKey 封装的密钥数据
 * @param algorithm 解封算法
 * @param keyLabel 导入后的密钥标签
 * @return Result包含：
 *   - unwrappedKeyHandle: 解封后的密钥句柄
 */
Result<KeyHandle> unwrapKey(
    SessionHandle session,
    KeyHandle kekHandle,
    byte[] wrappedKey,
    AlgorithmType algorithm,
    String keyLabel
);
```

**SSL应用场景**：
- 会话恢复（Session Resumption）时保存/加载会话密钥
- 密钥备份与恢复

---

## 六、摘要与MAC接口

### 6.1 计算摘要

**功能描述**：计算数据的密码学哈希值（SM3/SHA256）。

**接口定义**：
```java
/**
 * 计算消息摘要
 * 
 * @param session 会话句柄
 * @param algorithm 摘要算法（SM3/SHA256）
 * @param data 待计算摘要的数据
 * @return Result包含：
 *   - digest: 摘要值（SM3为32字节，SHA256为32字节）
 */
Result<byte[]> digest(
    SessionHandle session,
    AlgorithmType algorithm,
    byte[] data
);
```

**SSL应用场景**：
- 计算握手消息的摘要（用于Finished消息）
- 签名前对数据预先计算摘要

---

### 6.2 流式摘要计算

**功能描述**：支持分块计算大数据的摘要。

**接口定义**：
```java
/**
 * 初始化摘要计算
 */
Result<DigestContext> digestInit(
    SessionHandle session,
    AlgorithmType algorithm
);

/**
 * 更新摘要数据
 */
Result<Void> digestUpdate(
    SessionHandle session,
    DigestContext context,
    byte[] data
);

/**
 * 完成摘要计算
 */
Result<byte[]> digestFinal(
    SessionHandle session,
    DigestContext context
);

typedef long DigestContext;
```

**SSL应用场景**：
```java
// 计算整个握手过程的消息摘要
DigestContext ctx = hsm.digestInit(session, AlgorithmType.SM3).data;

hsm.digestUpdate(session, ctx, clientHelloBytes);
hsm.digestUpdate(session, ctx, serverHelloBytes);
hsm.digestUpdate(session, ctx, serverCertificateBytes);
// ... 其他握手消息

byte[] handshakeHash = hsm.digestFinal(session, ctx).data;
```

---

### 6.3 计算HMAC

**功能描述**：计算基于哈希的消息认证码。

**接口定义**：
```java
/**
 * 计算HMAC
 * 
 * @param session 会话句柄
 * @param keyHandle HMAC密钥句柄
 * @param algorithm HMAC算法（HMAC_SM3/HMAC_SHA256）
 * @param data 待认证的数据
 * @return Result包含：
 *   - mac: MAC值（长度等于底层哈希函数输出）
 */
Result<byte[]> hmac(
    SessionHandle session,
    KeyHandle keyHandle,
    AlgorithmType algorithm,
    byte[] data
);
```

**SSL应用场景**：
- **Finished消息**：
  ```
  verify_data = PRF(master_secret, finished_label, Hash(handshake_messages))
  实际上PRF内部使用HMAC
  ```
- **记录层MAC**：
  ```
  MAC(seq_num + type + version + length + fragment)
  ```

**调用示例**：
```java
// 计算SSL记录的MAC
byte[] macData = concat(
    sequenceNumber,    // 8字节
    recordType,        // 1字节
    protocolVersion,   // 2字节
    recordLength,      // 2字节
    encryptedData      // 变长
);

Result<byte[]> macResult = hsm.hmac(
    session,
    clientWriteMacKeyHandle,
    AlgorithmType.HMAC_SM3,
    macData
);

byte[] recordMac = macResult.data; // 32字节（SM3）
```

---

### 6.4 验证HMAC

**功能描述**：验证HMAC的正确性（防止时序攻击）。

**接口定义**：
```java
/**
 * 验证HMAC
 * 
 * @param session 会话句柄
 * @param keyHandle HMAC密钥句柄
 * @param algorithm HMAC算法
 * @param data 原始数据
 * @param mac 待验证的MAC值
 * @return Result包含：
 *   - valid: MAC是否有效
 */
Result<Boolean> hmacVerify(
    SessionHandle session,
    KeyHandle keyHandle,
    AlgorithmType algorithm,
    byte[] data,
    byte[] mac
);
```

**安全要求**：
- 必须使用常量时间比较，防止时序攻击
- 硬件实现可自动防御侧信道攻击

---

## 七、随机数生成接口

### 7.1 生成随机数

**功能描述**：生成密码学安全的随机数。

**接口定义**：
```java
/**
 * 生成随机数
 * 
 * @param session 会话句柄
 * @param length 随机数长度（字节）
 * @param quality 随机数质量（TRNG/PRNG）
 * @return Result包含：
 *   - randomBytes: 随机字节数组
 */
Result<byte[]> generateRandom(
    SessionHandle session,
    int length,
    RandomQuality quality
);

enum RandomQuality {
    TRNG,  // 真随机数（True Random Number Generator）
    PRNG   // 伪随机数（Pseudo Random Number Generator）
}
```

**SSL应用场景**：

**场景1：ClientHello.random / ServerHello.random**
```java
// 生成32字节的客户端随机数
Result<byte[]> randomResult = hsm.generateRandom(
    session,
    32,
    RandomQuality.TRNG  // SSL握手必须使用TRNG
);
byte[] clientRandom = randomResult.data;
```

**场景2：生成预主密钥（RSA模式）**
```java
// TLS 1.2 RSA密钥交换：pre_master_secret = 0x0303 || 46字节随机数
byte[] version = new byte[]{0x03, 0x03};
byte[] random46 = hsm.generateRandom(session, 46, RandomQuality.TRNG).data;
byte[] preMasterSecret = concat(version, random46);
```

**场景3：生成IV**
```java
// 为CBC模式生成IV
byte[] iv = hsm.generateRandom(session, 16, RandomQuality.PRNG).data;
```

**性能要求**：
- TRNG速率：> 1MB/s
- PRNG速率：> 10MB/s
- 必须通过NIST SP 800-90B随机性测试

---

### 7.2 添加熵源

**功能描述**：向硬件随机数生成器添加外部熵。

**接口定义**：
```java
/**
 * 添加熵源（Seed Random）
 * 
 * @param session 会话句柄
 * @param entropy 熵数据
 * @return Result包含：
 *   - success: 是否成功
 */
Result<Boolean> seedRandom(
    SessionHandle session,
    byte[] entropy
);
```

**应用场景**：
- 初始化时使用系统熵池（/dev/random）增强硬件随机数质量

---

## 八、会话管理接口

### 8.1 打开会话

**功能描述**：打开与硬件设备的会话。

**接口定义**：
```java
/**
 * 打开会话
 * 
 * @param slotId 设备槽位ID
 * @param flags 会话标志（读写/只读）
 * @return Result包含：
 *   - sessionHandle: 会话句柄
 */
Result<SessionHandle> openSession(
    int slotId,
    SessionFlags flags
);

enum SessionFlags {
    READ_WRITE,  // 读写会话（可修改密钥）
    READ_ONLY    // 只读会话（仅使用密钥）
}
```

**SSL应用场景**：
```java
// 服务器启动时打开会话
Result<SessionHandle> sessionResult = hsm.openSession(
    0,  // 槽位0
    SessionFlags.READ_ONLY  // SSL服务器只需要读取密钥
);
SessionHandle session = sessionResult.data;
```

---

### 8.2 关闭会话

**功能描述**：关闭会话并释放资源。

**接口定义**：
```java
/**
 * 关闭会话
 * 
 * @param session 会话句柄
 * @return Result包含：
 *   - success: 是否成功
 */
Result<Boolean> closeSession(
    SessionHandle session
);
```

**注意事项**：
- 会话关闭后，该会话中的临时密钥将被销毁
- 建议使用连接池管理会话，避免频繁打开/关闭

---

### 8.3 登录

**功能描述**：使用PIN码登录硬件设备。

**接口定义**：
```java
/**
 * 登录
 * 
 * @param session 会话句柄
 * @param userType 用户类型（普通用户/安全管理员）
 * @param pin PIN码或密码
 * @return Result包含：
 *   - authenticated: 是否认证成功
 *   - remainingAttempts: 剩余尝试次数（失败时）
 */
Result<LoginResult> login(
    SessionHandle session,
    UserType userType,
    String pin
);

enum UserType {
    USER,  // 普通用户（可使用密钥）
    SO     // 安全管理员（可管理密钥）
}

struct LoginResult {
    boolean authenticated;
    int remainingAttempts;
}
```

**SSL应用场景**：
```java
// 服务器启动时登录硬件设备
Result<LoginResult> loginResult = hsm.login(
    session,
    UserType.USER,
    "123456"  // 从配置文件读取PIN
);

if (!loginResult.data.authenticated) {
    throw new RuntimeException("HSM登录失败，剩余次数: " + 
        loginResult.data.remainingAttempts);
}
```

---

### 8.4 登出

**功能描述**：登出并清除会话状态。

**接口定义**：
```java
/**
 * 登出
 * 
 * @param session 会话句柄
 * @return Result包含：
 *   - success: 是否成功
 */
Result<Boolean> logout(
    SessionHandle session
);
```

---

## 九、证书管理接口

### 9.1 导入证书

**功能描述**：将X.509证书导入硬件设备。

**接口定义**：
```java
/**
 * 导入证书
 * 
 * @param session 会话句柄
 * @param certLabel 证书标签
 * @param certData X.509证书数据（DER编码）
 * @param certType 证书类型（服务器证书/CA证书）
 * @param associatedKeyHandle 关联的私钥句柄（可选）
 * @return Result包含：
 *   - certHandle: 证书句柄
 */
Result<ObjectHandle> importCertificate(
    SessionHandle session,
    String certLabel,
    byte[] certData,
    CertificateType certType,
    KeyHandle associatedKeyHandle
);

enum CertificateType {
    USER_CERT,   // 用户证书
    CA_CERT,     // CA证书
    TRUSTED_CERT // 信任的根证书
}
```

**SSL应用场景**：
- 导入服务器签名证书和加密证书
- 导入信任的CA根证书
- 将证书与硬件中的私钥关联

---

### 9.2 查询证书

**功能描述**：根据标签或主体查询证书。

**接口定义**：
```java
/**
 * 查询证书
 * 
 * @param session 会话句柄
 * @param certLabel 证书标签（null表示查询所有）
 * @param subject 证书主体DN（可选）
 * @return Result包含：
 *   - certificates: 证书列表
 */
Result<List<CertificateInfo>> findCertificates(
    SessionHandle session,
    String certLabel,
    String subject
);

struct CertificateInfo {
    ObjectHandle certHandle;
    String label;
    String subject;
    String issuer;
    byte[] serialNumber;
    Date notBefore;
    Date notAfter;
    byte[] certData;  // DER编码的证书
}
```

---

### 9.3 删除证书

**功能描述**：删除硬件设备中的证书。

**接口定义**：
```java
/**
 * 删除证书
 * 
 * @param session 会话句柄
 * @param certHandle 证书句柄
 * @return Result包含：
 *   - success: 是否成功
 */
Result<Boolean> deleteCertificate(
    SessionHandle session,
    ObjectHandle certHandle
);
```

---

## 十、设备管理接口

### 10.1 获取设备信息

**功能描述**：查询硬件设备的基本信息。

**接口定义**：
```java
/**
 * 获取设备信息
 * 
 * @param slotId 槽位ID
 * @return Result包含设备信息
 */
Result<DeviceInfo> getDeviceInfo(int slotId);

struct DeviceInfo {
    String manufacturer;         // 厂商名称
    String model;                // 设备型号
    String serialNumber;         // 序列号
    String firmwareVersion;      // 固件版本
    int totalMemory;             // 总内存（KB）
    int freeMemory;              // 可用内存（KB）
    boolean hardwareRNG;         // 是否支持硬件随机数
    boolean tamperResistant;     // 是否防篡改
}
```

---

### 10.2 获取设备能力

**功能描述**：查询设备支持的算法和功能。

**接口定义**：
```java
/**
 * 获取设备能力
 * 
 * @param slotId 槽位ID
 * @return Result包含设备能力
 */
Result<DeviceCapabilities> getDeviceCapabilities(int slotId);

struct DeviceCapabilities {
    List<AlgorithmType> supportedAlgorithms;  // 支持的算法列表
    int maxSessionCount;                      // 最大会话数
    int maxKeyCount;                          // 最大密钥数
    boolean supportsSM2;                      // 支持SM2
    boolean supportsSM3;                      // 支持SM3
    boolean supportsSM4;                      // 支持SM4
    boolean supportsRSA;                      // 支持RSA
    boolean supportsAES;                      // 支持AES
    boolean supportsKeyGeneration;            // 支持密钥生成
    boolean supportsKeyWrap;                  // 支持密钥封装
    int sm2SignTPS;                           // SM2签名TPS
    int sm2DecryptTPS;                        // SM2解密TPS
    int sm4ThroughputMBps;                    // SM4吞吐量(MB/s)
}
```

**SSL应用场景**：
```java
// 启动时检查设备是否支持所需算法
DeviceCapabilities caps = hsm.getDeviceCapabilities(0).data;

if (!caps.supportsSM2 || !caps.supportsSM3 || !caps.supportsSM4) {
    throw new RuntimeException("硬件设备不支持国密算法");
}

System.out.println("设备SM2签名性能: " + caps.sm2SignTPS + " TPS");
```

---

### 10.3 健康检查

**功能描述**：检查设备健康状态。

**接口定义**：
```java
/**
 * 健康检查
 * 
 * @param slotId 槽位ID
 * @return Result包含健康状态
 */
Result<HealthStatus> healthCheck(int slotId);

struct HealthStatus {
    boolean online;              // 设备是否在线
    boolean responsive;          // 设备是否响应
    int temperature;             // 温度（℃）
    int errorCount;              // 错误计数
    long uptime;                 // 运行时长（秒）
    String lastError;            // 最后一次错误信息
}
```

**SSL应用场景**：
```java
// 定时健康检查
@Scheduled(fixedRate = 60000)
public void hsmHealthCheck() {
    HealthStatus health = hsm.healthCheck(0).data;
    
    if (!health.online || !health.responsive) {
        // 切换到软件模式或告警
        logger.error("HSM设备异常: " + health.lastError);
        switchToSoftwareMode();
    }
    
    // 监控温度
    if (health.temperature > 70) {
        logger.warn("HSM温度过高: " + health.temperature + "℃");
    }
}
```

---

### 10.4 初始化设备

**功能描述**：初始化设备（首次使用或重置）。

**接口定义**：
```java
/**
 * 初始化设备
 * 
 * @param slotId 槽位ID
 * @param soPin 安全管理员PIN（8-32位）
 * @param label 设备标签
 * @return Result包含：
 *   - success: 是否成功
 */
Result<Boolean> initializeDevice(
    int slotId,
    String soPin,
    String label
);
```

**注意事项**：
- 初始化会清除设备中的所有密钥和数据
- 仅在首次使用或需要完全重置时调用

---

## 十一、完整接口汇总

### 接口分类统计

| 类别 | 接口数量 | 核心接口 | SSL必需 |
|-----|---------|---------|---------|
| 密钥管理 | 6 | generateKeyPair, importKey, findKeys | ✅ |
| 非对称密码 | 4 | sign, decrypt | ✅ |
| 对称密码 | 6 | symmetricEncrypt, deriveKey | ✅ |
| 摘要与MAC | 6 | digest, hmac | ✅ |
| 随机数 | 2 | generateRandom | ✅ |
| 会话管理 | 4 | openSession, login | ✅ |
| 证书管理 | 3 | importCertificate, findCertificates | ⚠️ |
| 设备管理 | 4 | getDeviceInfo, healthCheck | ⚠️ |
| **总计** | **35** | **17** | **28** |

### 最小可用接口集（SSL服务器）

实现一个基本的国密SSL服务器，硬件设备**最少**需要提供以下接口：

```
核心（必须）：
1. openSession() - 打开会话
2. login() - 登录认证
3. findKeys() - 查找密钥
4. sign() - SM2签名（服务器身份认证）
5. decrypt() - SM2解密（密钥交换）
6. generateRandom() - 生成随机数
7. deriveKey() - 密钥派生（PRF）
8. symmetricEncrypt() - SM4加密（数据保护）
9. symmetricDecrypt() - SM4解密
10. hmac() - HMAC计算（消息认证）

扩展（建议）：
11. generateKeyPair() - 生成密钥对
12. digest() - SM3摘要
13. healthCheck() - 健康检查
14. closeSession() - 关闭会话
```

---

## 十二、Java JCE Provider实现示例

### 12.1 Provider注册

```java
package cn.byzk.hsm;

import java.security.Provider;

/**
 * HSM JCE Provider实现
 */
public class HSMProvider extends Provider {
    
    private static final String PROVIDER_NAME = "HSM";
    private static final double PROVIDER_VERSION = 1.0;
    private static final String PROVIDER_INFO = 
        "Hardware Security Module JCE Provider v1.0";
    
    public HSMProvider() {
        super(PROVIDER_NAME, PROVIDER_VERSION, PROVIDER_INFO);
        
        // 注册支持的算法
        putService(new Service(this, "KeyPairGenerator", "SM2",
            "cn.byzk.hsm.SM2KeyPairGenerator", null, null));
        
        putService(new Service(this, "Signature", "SM3withSM2",
            "cn.byzk.hsm.SM2Signature", null, null));
        
        putService(new Service(this, "Cipher", "SM2",
            "cn.byzk.hsm.SM2Cipher", null, null));
        
        putService(new Service(this, "Cipher", "SM4/CBC/PKCS7Padding",
            "cn.byzk.hsm.SM4Cipher", null, null));
        
        putService(new Service(this, "MessageDigest", "SM3",
            "cn.byzk.hsm.SM3MessageDigest", null, null));
        
        putService(new Service(this, "Mac", "HmacSM3",
            "cn.byzk.hsm.HmacSM3", null, null));
        
        putService(new Service(this, "KeyStore", "PKCS11",
            "cn.byzk.hsm.HSMKeyStore", null, null));
        
        putService(new Service(this, "SecureRandom", "HSM",
            "cn.byzk.hsm.HSMSecureRandom", null, null));
    }
}
```

### 12.2 SM2签名实现

```java
package cn.byzk.hsm;

import java.security.*;
import java.security.spec.*;

public class SM2Signature extends SignatureSpi {
    
    private HSMClient hsmClient;
    private SessionHandle session;
    private KeyHandle privateKeyHandle;
    private byte[] digest;
    
    @Override
    protected void engineInitSign(PrivateKey privateKey) 
            throws InvalidKeyException {
        if (!(privateKey instanceof HSMPrivateKey)) {
            throw new InvalidKeyException("Not a HSM private key");
        }
        
        HSMPrivateKey hsmKey = (HSMPrivateKey) privateKey;
        this.privateKeyHandle = hsmKey.getKeyHandle();
        this.session = hsmKey.getSession();
        this.digest = null;
    }
    
    @Override
    protected void engineUpdate(byte[] b, int off, int len) {
        // 累积待签名数据，计算SM3摘要
        if (digest == null) {
            digest = new byte[32];
        }
        // 调用HSM的digestUpdate
        Result<Void> result = hsmClient.digestUpdate(session, b, off, len);
        if (result.errorCode != ErrorCode.SUCCESS) {
            throw new SignatureException("Digest update failed");
        }
    }
    
    @Override
    protected byte[] engineSign() throws SignatureException {
        // 完成摘要计算
        Result<byte[]> digestResult = hsmClient.digestFinal(session);
        if (digestResult.errorCode != ErrorCode.SUCCESS) {
            throw new SignatureException("Digest computation failed");
        }
        
        // 调用硬件签名
        Result<byte[]> signResult = hsmClient.sign(
            session,
            privateKeyHandle,
            AlgorithmType.SM2,
            AlgorithmType.SM3,
            digestResult.data,
            true  // 已计算摘要
        );
        
        if (signResult.errorCode != ErrorCode.SUCCESS) {
            throw new SignatureException("Signature failed: " + 
                signResult.errorMessage);
        }
        
        return signResult.data;  // DER编码的(r,s)
    }
    
    @Override
    protected boolean engineVerify(byte[] sigBytes) 
            throws SignatureException {
        // 验签实现（类似）
        Result<Boolean> verifyResult = hsmClient.verify(
            session, publicKeyHandle, algorithm, digestAlgo, 
            digest, sigBytes, true);
        return verifyResult.data;
    }
    
    // 其他方法实现...
}
```

### 12.3 HSM KeyStore实现

```java
package cn.byzk.hsm;

import java.security.KeyStoreSpi;
import java.security.Key;
import java.security.cert.Certificate;

public class HSMKeyStore extends KeyStoreSpi {
    
    private HSMClient hsmClient;
    private SessionHandle session;
    
    @Override
    public void engineLoad(InputStream stream, char[] password) 
            throws IOException {
        // PKCS11 KeyStore不从文件加载
        // 而是连接到硬件设备
        try {
            Result<SessionHandle> sessionResult = 
                hsmClient.openSession(0, SessionFlags.READ_ONLY);
            this.session = sessionResult.data;
            
            // 使用PIN登录
            Result<LoginResult> loginResult = 
                hsmClient.login(session, UserType.USER, 
                    new String(password));
            
            if (!loginResult.data.authenticated) {
                throw new IOException("HSM login failed");
            }
        } catch (Exception e) {
            throw new IOException("Failed to connect to HSM", e);
        }
    }
    
    @Override
    public Key engineGetKey(String alias, char[] password) {
        // 根据alias查找密钥
        Result<List<KeyHandle>> findResult = 
            hsmClient.findKeys(session, alias, null);
        
        if (findResult.data.isEmpty()) {
            return null;
        }
        
        KeyHandle keyHandle = findResult.data.get(0);
        
        // 获取密钥属性
        Result<KeyAttributes> attrResult = 
            hsmClient.getKeyAttributes(session, keyHandle);
        
        // 返回HSM私钥包装对象
        return new HSMPrivateKey(keyHandle, session, alias, 
            attrResult.data.algorithm);
    }
    
    @Override
    public Certificate[] engineGetCertificateChain(String alias) {
        // 查找证书链
        Result<List<CertificateInfo>> certResult = 
            hsmClient.findCertificates(session, alias, null);
        
        if (certResult.data.isEmpty()) {
            return null;
        }
        
        // 解析证书
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return certResult.data.stream()
            .map(info -> cf.generateCertificate(
                new ByteArrayInputStream(info.certData)))
            .toArray(Certificate[]::new);
    }
    
    // 其他方法实现...
}
```

### 12.4 使用示例

```java
// 1. 注册HSM Provider
Security.addProvider(new HSMProvider());

// 2. 加载KeyStore
KeyStore keyStore = KeyStore.getInstance("PKCS11", "HSM");
keyStore.load(null, "123456".toCharArray());

// 3. 获取私钥
PrivateKey privateKey = (PrivateKey) keyStore.getKey(
    "server_sign_key", null);

// 4. 创建签名
Signature signature = Signature.getInstance("SM3withSM2", "HSM");
signature.initSign(privateKey);
signature.update(data);
byte[] signBytes = signature.sign();

// 5. 对称加密
KeyGenerator kg = KeyGenerator.getInstance("SM4", "HSM");
SecretKey sm4Key = kg.generateKey();

Cipher cipher = Cipher.getInstance("SM4/CBC/PKCS7Padding", "HSM");
cipher.init(Cipher.ENCRYPT_MODE, sm4Key);
byte[] encrypted = cipher.doFinal(plaintext);
```

---

## 附录A：错误码完整定义

```java
public enum ErrorCode {
    // 成功
    SUCCESS(0x00000000, "操作成功"),
    
    // 通用错误 (0x0001-0x00FF)
    GENERAL_ERROR(0x00000001, "通用错误"),
    NOT_IMPLEMENTED(0x00000002, "功能未实现"),
    INVALID_PARAMETER(0x00000003, "参数无效"),
    BUFFER_TOO_SMALL(0x00000004, "缓冲区太小"),
    
    // 会话错误 (0x0100-0x01FF)
    SESSION_INVALID(0x00000100, "会话无效"),
    SESSION_CLOSED(0x00000101, "会话已关闭"),
    SESSION_COUNT_EXCEEDED(0x00000102, "会话数超限"),
    SESSION_READ_ONLY(0x00000103, "会话只读"),
    
    // 认证错误 (0x0200-0x02FF)
    PIN_INCORRECT(0x00000200, "PIN码错误"),
    PIN_LOCKED(0x00000201, "PIN已锁定"),
    PIN_EXPIRED(0x00000202, "PIN已过期"),
    USER_NOT_LOGGED_IN(0x00000203, "用户未登录"),
    USER_ALREADY_LOGGED_IN(0x00000204, "用户已登录"),
    
    // 密钥错误 (0x0300-0x03FF)
    KEY_NOT_FOUND(0x00000300, "密钥未找到"),
    KEY_HANDLE_INVALID(0x00000301, "密钥句柄无效"),
    KEY_TYPE_MISMATCH(0x00000302, "密钥类型不匹配"),
    KEY_SIZE_INVALID(0x00000303, "密钥长度无效"),
    KEY_NOT_EXPORTABLE(0x00000304, "密钥不可导出"),
    KEY_USAGE_VIOLATION(0x00000305, "密钥用途违规"),
    
    // 算法错误 (0x0400-0x04FF)
    ALGORITHM_NOT_SUPPORTED(0x00000400, "算法不支持"),
    MECHANISM_INVALID(0x00000401, "机制无效"),
    OPERATION_NOT_PERMITTED(0x00000402, "操作不允许"),
    
    // 密码操作错误 (0x0500-0x05FF)
    SIGNATURE_INVALID(0x00000500, "签名无效"),
    SIGNATURE_LENGTH_INVALID(0x00000501, "签名长度无效"),
    DECRYPTION_FAILED(0x00000502, "解密失败"),
    ENCRYPTION_FAILED(0x00000503, "加密失败"),
    MAC_INVALID(0x00000504, "MAC无效"),
    
    // 设备错误 (0x0600-0x06FF)
    DEVICE_ERROR(0x00000600, "设备错误"),
    DEVICE_NOT_PRESENT(0x00000601, "设备不存在"),
    DEVICE_REMOVED(0x00000602, "设备已移除"),
    DEVICE_BUSY(0x00000603, "设备繁忙"),
    DEVICE_TIMEOUT(0x00000604, "设备超时"),
    
    // 资源错误 (0x0700-0x07FF)
    MEMORY_ERROR(0x00000700, "内存错误"),
    STORAGE_FULL(0x00000701, "存储空间已满"),
    RESOURCE_EXHAUSTED(0x00000702, "资源耗尽");
    
    private final int code;
    private final String message;
    
    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
    
    public int getCode() { return code; }
    public String getMessage() { return message; }
}
```

---

## 附录B：参考标准

### 国际标准
- PKCS#11 v2.40: Cryptographic Token Interface Standard
- RFC 5246: TLS 1.2
- RFC 8446: TLS 1.3
- FIPS 140-2/140-3: Security Requirements for Cryptographic Modules

### 国密标准
- GM/T 0003-2012: SM2椭圆曲线公钥密码算法
- GM/T 0004-2012: SM3密码杂凑算法
- GM/T 0002-2012: SM4分组密码算法
- GM/T 0024-2014: SSL VPN技术规范
- GM/T 0009-2012: SM2密码算法使用规范
- GM/T 0015-2012: 基于SM2密码算法的数字证书格式规范

---

## 附录C：性能基准

### 典型硬件设备性能指标

| 操作 | PCI-E卡 | USB设备 | 网络加密机 |
|------|--------|---------|-----------|
| SM2签名 | 5000 TPS | 500 TPS | 2000 TPS |
| SM2验签 | 8000 TPS | 800 TPS | 3000 TPS |
| SM2解密 | 3000 TPS | 300 TPS | 1500 TPS |
| SM4加密 | 500 MB/s | 50 MB/s | 200 MB/s |
| SM3摘要 | 800 MB/s | 80 MB/s | 300 MB/s |
| 随机数 | 2 MB/s | 0.5 MB/s | 1 MB/s |

### SSL握手性能估算

假设TLCP握手需要：
- 1次SM2签名（服务器身份认证）
- 1次SM2解密（密钥交换）
- 3次SM3摘要
- 2次随机数生成
- 1次密钥派生

使用PCI-E卡，理论TPS：
```
瓶颈 = min(签名TPS, 解密TPS) = min(5000, 3000) = 3000
实际TPS ≈ 2500（考虑其他开销）
```

---

## 变更历史

| 版本 | 日期 | 作者 | 变更说明 |
|------|------|------|---------|
| v1.0 | 2025-10-20 | AI助手 | 初始版本 |

---

**文档状态**: ✅ 已完成  
**审核状态**: ⏳ 待审核  
**适用版本**: JDK 17+, Spring Boot 3.x






