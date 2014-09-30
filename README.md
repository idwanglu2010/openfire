基于openfire3.9.3
添加了BroadcastService插件，提供了HTTP接口用于向单个用户、聊天室、所有用户、用户组push消息
========
1、check out 代码

2、import Java Project 到Eclipse,import时选择Copy projects into workspace

3、打开cmd，到工程目录..\workspace\openfire\build下，输入命令ant,ant脚本会自动构建openfire,输出work和target目录

4、右键工程-->run as-->Run Configurations-->Java Application-->右键-->New
   弹出配置窗口，选中Main选项卡，Name:openfire,Project:openfire,Main class:org.jivesoftware.openfire.starter.ServerStarter
   选中Arguments,VM arguments:-DopenfireHome="${workspace_loc:openfire}/target/openfire"
   
5、Run

6、在Console窗口输出日志，Admin console listening at http://example.com:9090

7、浏览器打开http://example.com:9090 进行配置


另外说明：
在build目录下，使用ant plugins可以部署安装插件


详细安装部署、插件开发等还请移步openfire官方文档：

https://www.igniterealtime.org/projects/openfire/documentation.jsp
