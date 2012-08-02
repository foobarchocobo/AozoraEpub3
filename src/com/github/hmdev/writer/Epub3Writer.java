package com.github.hmdev.writer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Vector;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;

import com.github.hmdev.converter.AozoraEpub3Converter;
import com.github.hmdev.info.BookInfo;
import com.github.hmdev.info.ChapterInfo;
import com.github.hmdev.info.ImageInfo;
import com.github.hmdev.info.SectionInfo;
import com.github.hmdev.util.LogAppender;

/** ePub3用のファイル一式をZipで固めたファイルを生成.
 * 本文は改ページでセクション毎に分割されて xhtml/以下に 0001.xhtml 0002.xhtml の連番ファイル名で格納
 * 画像は images/以下に 0001.jpg 0002.png のようにリネームして格納
 */
public class Epub3Writer
{
	/** ORBパス */
	final static String OPS_PATH = "OPS/";
	/** 画像格納パス */
	final static String IMAGES_PATH = "images/";
	/** CSS格納パス */
	final static String CSS_PATH = "css/";
	/** xhtml格納パス */
	final static String XHTML_PATH = "xhtml/";
	
	/** xhtmlヘッダVelocityテンプレート */
	final static String XHTML_HEADER_VM = "xhtml_header.vm";
	/** xhtmlフッタVelocityテンプレート */
	final static String XHTML_FOOTER_VM = "xhtml_footer.vm";
	
	/** navファイル 未対応 */
	final static String XHTML_NAV_FILE = "nav.xhtml";
	/** navファイル Velocityテンプレート 未対応 */
	final static String XHTML_NAV_VM = "xhtml_nav.vm";
	
	/** opfファイル */
	final static String PACKAGE_FILE = "package.opf";
	/** opfファイル Velocityテンプレート */
	final static String PACKAGE_VM = "package.vm";
	
	/** tocファイル */
	final static String TOC_FILE = "toc.ncx";
	/** tocファイル Velocityテンプレート */
	final static String TOC_VM = "toc.ncx.vm";
	
	/** コピーのみのファイル */
	final static String[] TEMPLATE_FILE_NAMES = new String[]
		{"mimetype", "META-INF/container.xml", OPS_PATH+CSS_PATH+"vertical.css", OPS_PATH+CSS_PATH+"vertical_image.css", OPS_PATH+CSS_PATH+"horizontal.css", OPS_PATH+CSS_PATH+"horizontal_image.css"};
	
	/** 出力先ePubのZipストリーム */
	ZipArchiveOutputStream zos;
	
	/** ファイル名桁揃え用 */
	public static DecimalFormat decimalFormat = new DecimalFormat("0000");
	/** 更新日時フォーマット 2011-06-29T12:00:00Z */
	SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
	
	/** セクション番号自動追加用インデックス */
	int sectionIndex = 0;
	/** 画像番号自動追加用インデックス */
	int imageIndex = 0;
	
	/** 改ページでセクション分割されたセクション番号(0001)を格納 カバー画像(cover)等も含む */
	Vector<SectionInfo> sectionInfos;
	/** 章の名称を格納(仮) */
	Vector<ChapterInfo> chapterInfos;
	
	/** 画像情報リスト Velocity埋め込み */
	Vector<ImageInfo> imageInfos;
	/** 画像リネーム情報格納用 重複チェック用にHashに格納 */
	HashMap<String, String> imageFileNames;
	
	/** Velocity変数格納コンテキスト */
	VelocityContext velocityContext;
	
	/** テンプレートパス */
	String templatePath;
	
	/** 出力中の書籍情報 */
	BookInfo bookInfo;
	
	/** コンストラクタ
	 * @param templatePath epubテンプレート格納パス文字列 最後は"/"
	 */
	public Epub3Writer(String templatePath)
	{
		this.templatePath = templatePath;
	}
	
	/** epubファイルを出力
	 * @param converter 青空文庫テキスト変換クラス
	 * @param src 青空文庫テキストファイルの入力Stream
	 * @param srcFile 青空文庫テキストファイル zip時の画像取得用
	 * @param epubFile 出力ファイル .epub拡張子
	 * @param bookInfo 書籍情報と縦横書き指定
	 * @throws IOException */
	public void write(AozoraEpub3Converter converter, BufferedReader src, File srcFile, String zipTextFileName, File epubFile, BookInfo bookInfo) throws IOException
	{
		String srcPath = srcFile.getParent()+"/";
		this.bookInfo = bookInfo;
		
		//インデックス初期化
		this.sectionIndex = 0;
		this.imageIndex = 0;
		this.sectionInfos = new Vector<SectionInfo>();
		this.chapterInfos = new Vector<ChapterInfo>();
		this.imageInfos = new Vector<ImageInfo>();
		this.imageFileNames = new HashMap<String, String>();
		
		//初回実行時のみ有効
		Velocity.init();
		//Velocity用 共通コンテキスト設定
		this.velocityContext = new VelocityContext();
		//IDはタイトル著作者のハッシュで適当に生成
		String title = bookInfo.title==null?"":bookInfo.title;
		String creator = bookInfo.creator==null?"":bookInfo.creator;
		//固有ID
		velocityContext.put("identifier", UUID.nameUUIDFromBytes((title+"-"+creator).getBytes()));
		//目次名称
		velocityContext.put("toc", "目次");
		//書籍情報 タイトル、著作者、縦書き
		velocityContext.put("bookInfo", bookInfo);
		velocityContext.put("modified", dateFormat.format(bookInfo.modified));
		
		//出力先ePubのZipストリーム生成
		zos = new ZipArchiveOutputStream(new BufferedOutputStream(new FileOutputStream(epubFile)));
		//mimetypeは非圧縮
		zos.setLevel(0);
		//テンプレートのファイルを格納
		for (String fileName : TEMPLATE_FILE_NAMES) {
			zos.putArchiveEntry(new ZipArchiveEntry(fileName));
			FileInputStream fis = new FileInputStream(new File(templatePath+fileName));
			IOUtils.copy(fis, zos);
			fis.close();
			zos.closeArchiveEntry();
			zos.setLevel(9);
		}
		
		//0001CapterのZipArchiveEntryを設定
		this.startSection(0);
		
		//ePub3変換して出力
		//改ページ時にnextSection() を、画像出力時にgetImageFilePath() 呼び出し
		BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(zos, "UTF-8"));
		converter.convertTextToEpub3(bw, src, bookInfo);
		bw.flush();
		
		this.endSection();
		
		ImageInfo coverImageInfo = null;
		if (bookInfo.coverFileName == null) {
			//表紙無し
			//表紙設定解除
			for(ImageInfo imageInfo2 : imageInfos) {
				imageInfo2.setIsCover(false);
			}
		}
		else if (bookInfo.coverFileName.length() > 0) {
			//表紙情報をimageInfosに追加
			try {
				//表紙設定解除
				for(ImageInfo imageInfo2 : imageInfos) {
					imageInfo2.setIsCover(false);
				}
				String ext = "";
				try { ext = bookInfo.coverFileName.substring(bookInfo.coverFileName.lastIndexOf('.')+1); } catch (Exception e) {}
				String imageId = "cover";
				String format = "image/"+ext.toLowerCase();
				if ("image/jpg".equals(format)) format = "image/jpeg";
				if (!format.matches("^image\\/(png|jpeg|gif)$")) LogAppender.append("表紙画像フォーマットエラー: "+bookInfo.coverFileName+"\n");
				else {
					coverImageInfo = new ImageInfo(imageId, imageId+"."+ext, format);
					coverImageInfo.setIsCover(true);
					this.imageInfos.add(0, coverImageInfo);
				}
			} catch (Exception e) { e.printStackTrace(); }
		}
		
		//package.opf 出力
		velocityContext.put("sections", sectionInfos);
		velocityContext.put("images", imageInfos);
		zos.putArchiveEntry(new ZipArchiveEntry(OPS_PATH+PACKAGE_FILE));
		bw = new BufferedWriter(new OutputStreamWriter(zos, "UTF-8"));
		Velocity.getTemplate(templatePath+OPS_PATH+PACKAGE_VM).merge(velocityContext, bw);
		bw.flush();
		zos.closeArchiveEntry();
		
		//navファイル
		velocityContext.put("chapters", chapterInfos);
		zos.putArchiveEntry(new ZipArchiveEntry(OPS_PATH+XHTML_PATH+XHTML_NAV_FILE));
		bw = new BufferedWriter(new OutputStreamWriter(zos, "UTF-8"));
		Velocity.getTemplate(templatePath+OPS_PATH+XHTML_PATH+XHTML_NAV_VM).merge(velocityContext, bw);
		bw.flush();
		zos.closeArchiveEntry();
		
		//tocファイル
		velocityContext.put("chapters", chapterInfos);
		zos.putArchiveEntry(new ZipArchiveEntry(OPS_PATH+TOC_FILE));
		bw = new BufferedWriter(new OutputStreamWriter(zos, "UTF-8"));
		Velocity.getTemplate(templatePath+OPS_PATH+TOC_VM).merge(velocityContext, bw);
		bw.flush();
		zos.closeArchiveEntry();
		
		src.close();
		
		zos.setLevel(0);
		//画像ファイルコピー (連番にリネーム)
		//表紙指定があればそれを入力に設定 先頭画像のisCoverはfalseに
		//表紙
		if (coverImageInfo != null) {
			try {
				BufferedInputStream bis;
				if (bookInfo.coverFileName.startsWith("http")) {
					bis = new BufferedInputStream(new URL(bookInfo.coverFileName).openStream());
				} else {
					bis = new BufferedInputStream(new FileInputStream(new File(bookInfo.coverFileName)));
				}
				zos.putArchiveEntry(new ZipArchiveEntry(OPS_PATH+IMAGES_PATH+imageInfos.get(0).getFile()));
				IOUtils.copy(bis, zos);
				zos.closeArchiveEntry();
				bis.close();
				imageInfos.remove(0);//カバー画像情報削除
			} catch (Exception e) {
				e.printStackTrace();
				LogAppender.append("表紙画像取得エラー: "+bookInfo.coverFileName+"\n");
			}
		}
		if (srcFile.getName().toLowerCase().endsWith(".zip")) {
			int zipPathLength = zipTextFileName.indexOf('/')+1;
			ZipArchiveInputStream zis = new ZipArchiveInputStream(new BufferedInputStream(new FileInputStream(srcFile)), "Shift_JIS", false);
			ArchiveEntry entry;
			while( (entry = zis.getNextEntry()) != null ) {
				String entryName = entry.getName().substring(zipPathLength);
				String dstFilePath = imageFileNames.get(entryName);
				if (dstFilePath != null) {
					zos.putArchiveEntry(new ZipArchiveEntry(OPS_PATH+dstFilePath));
					IOUtils.copy(zis, zos);
					zos.closeArchiveEntry();
					//チェック用に出力したら削除
					imageFileNames.remove(entryName);
				}
			}
			zis.close();
			//出力されなかった画像をログ出力
			for(Map.Entry<String, String> e : imageFileNames.entrySet()) {
				LogAppender.append("画像ファイルなし: "+e.getKey()+"\n");
			}
		} else {
			//TODO 出力順をimageFiles順にする?
			for(Map.Entry<String, String> e : imageFileNames.entrySet()) {
				String srcFilePath = e.getKey();
				String dstFilePath = e.getValue();
				File imageFile = new File(srcPath+srcFilePath);
				if (imageFile.exists()) {
					zos.putArchiveEntry(new ZipArchiveEntry(OPS_PATH+dstFilePath));
					FileInputStream fis = new FileInputStream(imageFile);
					IOUtils.copy(fis, zos);
					zos.closeArchiveEntry();
					fis.close();
				} else {
					LogAppender.append("画像ファイルなし: "+srcFilePath+"\n");
				}
			}
		}
		
		//ePub3出力ファイルを閉じる
		zos.close();
	}
	
	/** 次のチャプター用のZipArchiveEntryに切替え 
	 * チャプターのファイル名はcpaterFileNamesに追加される (0001)
	 * @throws IOException */
	public void nextSection(BufferedWriter bw, int lineNum) throws IOException
	{
		bw.flush();
		this.endSection();
		this.startSection(lineNum);
	}
	/** セクション開始. 
	 * @throws IOException */
	private void startSection(int lineNum) throws IOException
	{
		this.sectionIndex++;
		String sectionId = decimalFormat.format(this.sectionIndex);
		//package.opf用にファイル名
		SectionInfo sectionInfo = new SectionInfo(sectionId);
		if (this.bookInfo.isImageSectionLine(lineNum)) sectionInfo.setImageFit(true);
		this.sectionInfos.add(sectionInfo);
		this.addChapter(sectionId, sectionId); //章の名称はsectionIdを仮に設定
		
		this.zos.putArchiveEntry(new ZipArchiveEntry(OPS_PATH+XHTML_PATH+sectionId+".xhtml"));
		
		//ヘッダ出力
		BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(zos, "UTF-8"));
		//出力開始するセクションに対応したSectionInfoを設定
		this.velocityContext.put("sectionInfo", sectionInfo);
		Velocity.getTemplate(this.templatePath+OPS_PATH+XHTML_PATH+XHTML_HEADER_VM).merge(this.velocityContext, bw);
		bw.flush();
	}
	/** セクション終了. 
	 * @throws IOException */
	private void endSection() throws IOException
	{
		//フッタ出力
		BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(zos, "UTF-8"));
		Velocity.getTemplate(this.templatePath+OPS_PATH+XHTML_PATH+XHTML_FOOTER_VM).merge(this.velocityContext, bw);
		bw.flush();
		
		this.zos.closeArchiveEntry();
	}
	/** 章を追加 */
	public void addChapter(String chapterId, String name)
	{
		SectionInfo sectionInfo = this.sectionInfos.lastElement();
		this.chapterInfos.add(new ChapterInfo(sectionInfo.sectionId, chapterId, name));
	}
	/** 追加済の章の名称を変更 */
	public void updateChapterName(String name)
	{
		this.chapterInfos.lastElement().setChapterName(name);
	}
	
	/** 連番に変更した画像ファイル名を返却.
	 * 重複していたら前に出力したときの連番ファイル名を返す
	 * 返り値はxhtmlからの相対パスにする (../images/0001.jpg)
	 * 変更前と変更後のファイル名はimageFileNamesに格納される (images/0001.jpg)
	 *  */
	public String getImageFilePath(String srcFilePath)
	{
		String ext = "";
		try { ext = srcFilePath.substring(srcFilePath.lastIndexOf('.')+1); } catch (Exception e) {}
		
		//すでに出力済みの画像
		String imageFileName = this.imageFileNames.get(srcFilePath);
		
		if (imageFileName == null) {
			this.imageIndex++;
			String imageId = decimalFormat.format(this.imageIndex);
			imageFileName = IMAGES_PATH+imageId+"."+ext;
			this.imageFileNames.put(srcFilePath, imageFileName);
			String format = "image/"+ext.toLowerCase();
			if ("image/jpg".equals(format)) format = "image/jpeg";
			if (!format.matches("^image\\/(png|jpeg|gif)$")) LogAppender.append("画像フォーマットエラー: "+srcFilePath+"\n");
			else {
				ImageInfo imageInfo = new ImageInfo(imageId, imageId+"."+ext, format);
				if (this.imageIndex == 1) imageInfo.setIsCover(true);
				this.imageInfos.add(imageInfo);
			}
		}
		return "../"+imageFileName;
	}
}
