package com.entrip.service

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.DeleteObjectRequest
import com.entrip.s3.S3Uploader
import com.entrip.domain.UploadedPhotoInformation
import com.entrip.domain.dto.Photos.PhotosReturnDto
import com.entrip.domain.entity.Photos
import com.entrip.domain.entity.Posts
import com.entrip.domain.entity.isCommunity
import com.entrip.repository.PhotosRepository
import com.entrip.repository.PostsRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDateTime
import javax.transaction.Transactional

@Service
class PhotosService(
    final val photosRepository: PhotosRepository,

    @Autowired
    final val amazonS3Client: AmazonS3Client,

    @Autowired
    val postsRepository: PostsRepository,

    @Autowired
    final val s3Uploader: S3Uploader,

    @Value("#{awsS3['cloud.aws.s3.bucket']}")
    private val bucket: String

) {
    private val logger: Logger = LoggerFactory.getLogger(PhotosService::class.java)

    private fun findPhotos(photo_id: Long): Photos = photosRepository.findById(photo_id).orElseThrow {
        IllegalArgumentException("Error raise at photoRepository.findById")
    }

    private fun findPosts(post_id: Long): Posts = postsRepository.findById(post_id).orElseThrow {
        IllegalArgumentException("Error raise at postsRepsotiroy.findById $post_id")
    }

    @Scheduled(fixedDelay = 1000000)
    protected fun deleteOrphanPhotos() {
        var photosList = photosRepository.findAll()
        var iterator = photosList.iterator()

        while (iterator.hasNext()) {
            val photos = iterator.next()
            val createdDate = photos.createdDate
            val currentDate = LocalDateTime.now()
            if (createdDate.plusMinutes(10)
                    .isBefore(currentDate) && photos.posts == null && photos.isCommunity()
            ) delete(photos.photo_id!!)
        }
        logger.info("deleteOrphanPhotos!")
    }


    @Transactional
    public fun save(photoUrl: String, fileName: String, priority: Long): Long? {
        val photos: Photos = Photos(
            photoUrl = photoUrl,
            fileName = fileName,
            priority = priority
        )
        photosRepository.save(photos)
        return photos.photo_id
    }

    public fun uploadAtS3(multipartFile: MultipartFile, priority: Long): Long? {
        val uploadedPhotoInformation: UploadedPhotoInformation = s3Uploader.upload(multipartFile, "static")
        val savedPhotoId =
            save(uploadedPhotoInformation.uploadImageUrl, uploadedPhotoInformation.uploadFileName, priority)
        return savedPhotoId
    }

    fun uploadAtS3(multipartFile: MultipartFile, priority: Long, directory: String): Long? {
        val uploadedPhotoInformation: UploadedPhotoInformation = s3Uploader.upload(multipartFile, directory)
        val savedPhotoId =
            save(uploadedPhotoInformation.uploadImageUrl, uploadedPhotoInformation.uploadFileName, priority)
        return savedPhotoId
    }

    public fun addPostsToPhotos(photo_id: Long, post_id: Long): Boolean {
        val photos = findPhotos(photo_id)
        val posts = findPosts(post_id)
        posts.photoSet!!.add(photos)
        photos.posts = posts
        return true
    }

    @Transactional
    public fun delete(photo_id: Long): Long {
        val photo = findPhotos(photo_id)
        val photoUrl = photo.photoUrl
        val fileName: String = photo.fileName

        deletePhotosInS3(fileName)
        deletePhotosInDataBase(photo)

        return photo_id
    }

    public fun findById(photo_id: Long): PhotosReturnDto = PhotosReturnDto(findPhotos(photo_id))

    private fun deletePhotosInS3(fileName: String) = amazonS3Client.deleteObject(DeleteObjectRequest(bucket, fileName))

    private fun deletePhotosInDataBase(photos: Photos) = photosRepository.delete(photos)
}